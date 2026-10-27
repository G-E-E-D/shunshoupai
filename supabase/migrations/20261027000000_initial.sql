-- Run once in a fresh Supabase project (SQL Editor or `supabase db push`).
-- Writes intentionally go through the two RPCs below, never directly to events.

create table public.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  timezone text not null default 'Asia/Shanghai' check (timezone = 'Asia/Shanghai')
);

create table public.events (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(title) between 1 and 120 and title = btrim(title)),
  start_at timestamptz not null,
  end_at timestamptz not null,
  timezone text not null default 'Asia/Shanghai' check (timezone = 'Asia/Shanghai'),
  status text not null default 'planned' check (status in ('planned', 'completed')),
  version integer not null default 1 check (version >= 1),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  check (isfinite(start_at) and isfinite(end_at)),
  check (end_at > start_at and end_at - start_at <= interval '24 hours')
);

create index events_user_window_idx on public.events(user_id, start_at, end_at)
  where deleted_at is null;

create table public.mutation_requests (
  user_id uuid not null references auth.users(id) on delete cascade,
  request_id uuid not null,
  payload_hash text not null,
  result jsonb not null,
  created_at timestamptz not null default now(),
  primary key (user_id, request_id)
);

create table public.ai_daily_usage (
  user_id uuid not null references auth.users(id) on delete cascade,
  usage_day date not null,
  call_count integer not null check (call_count between 1 and 100),
  primary key (user_id, usage_day)
);

alter table public.profiles enable row level security;
alter table public.events enable row level security;
alter table public.mutation_requests enable row level security;
alter table public.ai_daily_usage enable row level security;

create policy profiles_read_own on public.profiles for select to authenticated
  using (user_id = (select auth.uid()));
create policy events_read_own on public.events for select to authenticated
  using (user_id = (select auth.uid()));
create policy mutation_requests_read_own on public.mutation_requests for select to authenticated
  using (user_id = (select auth.uid()));
create policy ai_usage_read_own on public.ai_daily_usage for select to authenticated
  using (user_id = (select auth.uid()));

-- RLS alone is not enough: there are deliberately no direct write grants/policies.
revoke all on public.profiles, public.events, public.mutation_requests,
  public.ai_daily_usage from public, anon, authenticated;
grant select on public.profiles, public.events to authenticated;

create function public._ssp_new_profile() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles(user_id) values(new.id) on conflict do nothing;
  return new;
end;
$$;
create trigger shunshoupai_new_profile after insert on auth.users
  for each row execute function public._ssp_new_profile();
insert into public.profiles(user_id) select id from auth.users on conflict do nothing;

create function public._ssp_local_time(p_value text) returns timestamptz
language plpgsql immutable strict set search_path = '' as $$
begin
  if p_value !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])T([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$' then
    raise exception 'VALIDATION_ERROR: invalid local timestamp' using errcode = '22023';
  end if;
  if substring(p_value from 1 for 4)::integer not between 2000 and 2100 then
    raise exception 'VALIDATION_ERROR: supported years are 2000 to 2100' using errcode = '22023';
  end if;
  return p_value::timestamp without time zone at time zone 'Asia/Shanghai';
exception when invalid_datetime_format or datetime_field_overflow then
  raise exception 'VALIDATION_ERROR: invalid calendar date' using errcode = '22023';
end;
$$;

create function public.commit_events(p_request_id uuid, p_events jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  v_hash text;
  v_previous public.mutation_requests%rowtype;
  v_item jsonb;
  v_event public.events%rowtype;
  v_result jsonb := '[]'::jsonb;
  v_title text;
  v_start timestamptz;
  v_end timestamptz;
begin
  if v_user is null then
    raise exception 'UNAUTHORIZED' using errcode = '42501';
  end if;
  if p_request_id is null or jsonb_typeof(p_events) is distinct from 'array' then
    raise exception 'VALIDATION_ERROR: request_id and events required' using errcode = '22023';
  end if;
  if jsonb_array_length(p_events) not between 1 and 5 then
    raise exception 'VALIDATION_ERROR: submit 1 to 5 events' using errcode = '22023';
  end if;

  -- Canonical jsonb, so object-key order does not change the operation identity.
  v_hash := encode(sha256(convert_to(p_events::text, 'UTF8')), 'hex');
  -- All scheduling RPCs take the same per-owner transaction lock. Checking just
  -- the version of one row cannot detect another device inserting a new row.
  perform pg_advisory_xact_lock(hashtextextended(v_user::text, 0));
  select * into v_previous from public.mutation_requests
    where user_id = v_user and request_id = p_request_id;
  if found then
    if v_previous.payload_hash <> v_hash then
      raise exception 'IDEMPOTENCY_CONFLICT: request_id has different content';
    end if;
    return jsonb_set(v_previous.result, '{replayed}', 'true'::jsonb);
  end if;

  for v_item in select value from jsonb_array_elements(p_events) loop
    if jsonb_typeof(v_item) is distinct from 'object' then
      raise exception 'VALIDATION_ERROR: event must be an object' using errcode = '22023';
    end if;
    if v_item - array['title', 'start_local', 'end_local', 'timezone', 'time_source', 'assumptions'] <> '{}'::jsonb
       or jsonb_typeof(v_item->'title') is distinct from 'string'
       or jsonb_typeof(v_item->'start_local') is distinct from 'string'
       or jsonb_typeof(v_item->'end_local') is distinct from 'string'
       or (v_item->>'timezone') is distinct from 'Asia/Shanghai'
       or coalesce(v_item->>'time_source', '') not in ('explicit', 'suggested', 'assumed')
       or jsonb_typeof(v_item->'assumptions') is distinct from 'array' then
      raise exception 'VALIDATION_ERROR: invalid event fields' using errcode = '22023';
    end if;
    if jsonb_array_length(v_item->'assumptions') > 10 or exists (
      select 1 from jsonb_array_elements(v_item->'assumptions') a
      where jsonb_typeof(a.value) <> 'string' or char_length(a.value #>> '{}') > 300
    ) then
      raise exception 'VALIDATION_ERROR: invalid assumptions' using errcode = '22023';
    end if;

    v_title := btrim(v_item->>'title');
    v_start := public._ssp_local_time(v_item->>'start_local');
    v_end := public._ssp_local_time(v_item->>'end_local');
    if char_length(v_title) not between 1 and 120 or v_end <= v_start
       or v_end - v_start > interval '24 hours' then
      raise exception 'VALIDATION_ERROR: invalid title or duration' using errcode = '22023';
    end if;
    if exists (
      select 1 from public.events e where e.user_id = v_user and e.deleted_at is null
        and e.status = 'planned' and e.start_at < v_end and v_start < e.end_at
    ) then
      raise exception 'SCHEDULE_CONFLICT: refresh and choose another time';
    end if;
    insert into public.events(user_id, title, start_at, end_at, timezone)
      values(v_user, v_title, v_start, v_end, 'Asia/Shanghai') returning * into v_event;
    v_result := v_result || jsonb_build_array(to_jsonb(v_event) - 'user_id');
  end loop;
  v_result := jsonb_build_object('events', v_result, 'replayed', false);
  insert into public.mutation_requests(user_id, request_id, payload_hash, result)
    values(v_user, p_request_id, v_hash, v_result);
  return v_result;
end;
$$;

create function public.mutate_event(
  p_id uuid,
  p_base_version integer,
  p_patch jsonb,
  p_delete boolean default false
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  v_event public.events%rowtype;
  v_title text;
  v_start timestamptz;
  v_end timestamptz;
  v_status text;
begin
  if v_user is null then
    raise exception 'UNAUTHORIZED' using errcode = '42501';
  end if;
  if p_id is null or p_base_version is null or p_base_version < 1
     or p_delete is null or jsonb_typeof(p_patch) is distinct from 'object' then
    raise exception 'VALIDATION_ERROR: invalid mutation' using errcode = '22023';
  end if;
  if p_patch - array['title', 'start_local', 'end_local', 'status'] <> '{}'::jsonb
     or (not p_delete and p_patch = '{}'::jsonb)
     or (p_delete and p_patch <> '{}'::jsonb) then
    raise exception 'VALIDATION_ERROR: invalid patch fields' using errcode = '22023';
  end if;
  if exists (select 1 from jsonb_each(p_patch) a where jsonb_typeof(a.value) <> 'string') then
    raise exception 'VALIDATION_ERROR: patch values must be strings' using errcode = '22023';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(v_user::text, 0));
  select * into v_event from public.events where id = p_id and user_id = v_user for update;
  if not found then
    raise exception 'NOT_FOUND';
  end if;
  -- A lost DELETE response can be retried with exactly the original version.
  if v_event.deleted_at is not null and p_delete
      and p_base_version in (v_event.version, v_event.version - 1) then
    return jsonb_build_object('event', to_jsonb(v_event) - 'user_id');
  end if;
  if v_event.deleted_at is not null then
    raise exception 'NOT_FOUND';
  end if;
  if v_event.version <> p_base_version then
    raise exception 'VERSION_CONFLICT: refresh before editing again';
  end if;

  v_title := case when p_patch ? 'title' then btrim(p_patch->>'title') else v_event.title end;
  v_start := case when p_patch ? 'start_local' then public._ssp_local_time(p_patch->>'start_local') else v_event.start_at end;
  v_end := case when p_patch ? 'end_local' then public._ssp_local_time(p_patch->>'end_local') else v_event.end_at end;
  v_status := coalesce(p_patch->>'status', v_event.status);
  if char_length(v_title) not between 1 and 120 or v_end <= v_start
     or v_end - v_start > interval '24 hours' or v_status not in ('planned', 'completed') then
    raise exception 'VALIDATION_ERROR: invalid title, duration or status' using errcode = '22023';
  end if;
  if not p_delete and v_status = 'planned' and exists (
    select 1 from public.events e where e.user_id = v_user and e.id <> p_id
      and e.deleted_at is null and e.status = 'planned'
      and e.start_at < v_end and v_start < e.end_at
  ) then
    raise exception 'SCHEDULE_CONFLICT: refresh and choose another time';
  end if;
  update public.events set title = v_title, start_at = v_start, end_at = v_end,
    status = v_status, version = version + 1, updated_at = now(),
    deleted_at = case when p_delete then now() else null end
    where id = p_id and user_id = v_user returning * into v_event;
  return jsonb_build_object('event', to_jsonb(v_event) - 'user_id');
end;
$$;

create function public.consume_ai_quota(p_limit integer default 50) returns boolean
language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  v_count integer;
begin
  if v_user is null then
    raise exception 'UNAUTHORIZED' using errcode = '42501';
  end if;
  if p_limit is null or p_limit not between 1 and 100 then
    raise exception 'VALIDATION_ERROR: quota limit must be between 1 and 100' using errcode = '22023';
  end if;
  insert into public.ai_daily_usage(user_id, usage_day, call_count)
    values(v_user, (now() at time zone 'UTC')::date, 1)
    on conflict (user_id, usage_day) do update set call_count = public.ai_daily_usage.call_count + 1
      where public.ai_daily_usage.call_count < p_limit
    returning call_count into v_count;
  return v_count is not null;
end;
$$;

revoke all on function public._ssp_local_time(text) from public, anon, authenticated;
revoke all on function public._ssp_new_profile() from public, anon, authenticated;
revoke all on function public.commit_events(uuid, jsonb) from public, anon, authenticated;
revoke all on function public.mutate_event(uuid, integer, jsonb, boolean) from public, anon, authenticated;
revoke all on function public.consume_ai_quota(integer) from public, anon, authenticated;
grant execute on function public.commit_events(uuid, jsonb) to authenticated;
grant execute on function public.mutate_event(uuid, integer, jsonb, boolean) to authenticated;
grant execute on function public.consume_ai_quota(integer) to authenticated;

-- Polling/focus refresh works without Realtime. Enable separately if needed:
-- alter publication supabase_realtime add table public.events;
