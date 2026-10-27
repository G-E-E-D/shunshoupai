-- Durable mobile outbox receipts. Each retry must use the original payload.
create table public.sync_mutations (
  user_id uuid not null references auth.users(id) on delete cascade,
  mutation_id uuid not null,
  payload_hash text not null,
  result jsonb not null,
  primary key (user_id, mutation_id)
);
alter table public.sync_mutations enable row level security;
revoke all on public.sync_mutations from public, anon, authenticated;

create function public.sync_events(p_account_id uuid, p_mutations jsonb, p_after uuid default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  v_item jsonb;
  v_payload jsonb;
  v_id uuid;
  v_mutation uuid;
  v_base integer;
  v_deleted boolean;
  v_hash text;
  v_previous public.sync_mutations%rowtype;
  v_event public.events%rowtype;
  v_has_row boolean;
  v_result jsonb;
  v_results jsonb := '[]'::jsonb;
  v_events jsonb;
  v_more boolean;
  v_title text;
  v_start timestamptz;
  v_end timestamptz;
  v_status text;
  v_code text;
  v_message text;
begin
  if v_user is null then raise exception 'UNAUTHORIZED' using errcode = '42501'; end if;
  if p_account_id is distinct from v_user then raise exception 'ACCOUNT_MISMATCH'; end if;
  if jsonb_typeof(p_mutations) is distinct from 'array' or jsonb_array_length(p_mutations) > 20 then
    raise exception 'VALIDATION_ERROR';
  end if;
  if (select count(*) from jsonb_array_elements(p_mutations)) <>
     (select count(distinct value->>'mutation_id') from jsonb_array_elements(p_mutations)) then
    raise exception 'VALIDATION_ERROR: duplicate mutation id';
  end if;
  -- Same owner lock as online writes, so version checks cover both clients.
  perform pg_advisory_xact_lock(hashtextextended(v_user::text, 0));
  for v_item in select value from jsonb_array_elements(p_mutations) loop
    if jsonb_typeof(v_item) is distinct from 'object'
       or v_item - array['mutation_id','id','base_version','deleted','event'] <> '{}'::jsonb
       or jsonb_typeof(v_item->'mutation_id') is distinct from 'string'
       or jsonb_typeof(v_item->'id') is distinct from 'string'
       or jsonb_typeof(v_item->'base_version') is distinct from 'number'
       or coalesce(v_item->>'base_version','') !~ '^[0-9]{1,10}$'
       or jsonb_typeof(v_item->'deleted') is distinct from 'boolean' then
      raise exception 'VALIDATION_ERROR';
    end if;
    begin
      v_id := (v_item->>'id')::uuid;
      v_mutation := (v_item->>'mutation_id')::uuid;
      v_base := (v_item->>'base_version')::integer;
    exception when invalid_text_representation or numeric_value_out_of_range then
      raise exception 'VALIDATION_ERROR';
    end;
    if v_base > 2147483646 then raise exception 'VALIDATION_ERROR'; end if;
    v_deleted := (v_item->>'deleted')::boolean;
    if v_deleted then
      if v_base = 0 or v_item ? 'event' then raise exception 'VALIDATION_ERROR'; end if;
    else
      v_payload := v_item->'event';
      if jsonb_typeof(v_payload) is distinct from 'object'
         or v_payload - array['title','start_local','end_local','timezone','status'] <> '{}'::jsonb
         or jsonb_typeof(v_payload->'title') is distinct from 'string'
         or jsonb_typeof(v_payload->'start_local') is distinct from 'string'
         or jsonb_typeof(v_payload->'end_local') is distinct from 'string'
         or (v_payload->>'timezone') is distinct from 'Asia/Shanghai'
         or coalesce(v_payload->>'status','') not in ('planned','completed') then
        raise exception 'VALIDATION_ERROR';
      end if;
      v_title := btrim(v_payload->>'title');
      v_start := public._ssp_local_time(v_payload->>'start_local');
      v_end := public._ssp_local_time(v_payload->>'end_local');
      v_status := v_payload->>'status';
      if char_length(v_title) not between 1 and 120 or v_end <= v_start or v_end-v_start > interval '24 hours' then
        raise exception 'VALIDATION_ERROR';
      end if;
    end if;
    v_hash := encode(sha256(convert_to(v_item::text, 'UTF8')), 'hex');
    select * into v_previous from public.sync_mutations where user_id=v_user and mutation_id=v_mutation;
    if found then
      if v_previous.payload_hash <> v_hash then raise exception 'IDEMPOTENCY_CONFLICT'; end if;
      v_results := v_results || jsonb_build_array(v_previous.result);
      continue;
    end if;
    select * into v_event from public.events where user_id=v_user and id=v_id;
    v_has_row := found;
    v_code := null;
    v_message := null;
    if v_base = 0 and exists(select 1 from public.events where id=v_id) then
      v_code := 'ID_CONFLICT'; v_message := '这条日程编号已存在，手机内容已保留，请另存为新日程。';
    elsif v_base > 0 and not v_has_row then
      v_code := 'NOT_FOUND'; v_message := '这条日程已不存在，手机内容已保留。';
    elsif v_base > 0 and (v_event.version <> v_base or v_event.deleted_at is not null) then
      v_code := 'VERSION_CONFLICT'; v_message := '两台设备都修改了这条日程，手机内容已保留，请选择要使用的版本。';
    end if;
    if v_code is null then
      -- Distinct offline commitments are kept even if they overlap. A client
      -- can flag the overlap, but syncing must never discard either event.
      if v_base = 0 then
        begin
          insert into public.events(id,user_id,title,start_at,end_at,timezone,status)
            values(v_id,v_user,v_title,v_start,v_end,'Asia/Shanghai',v_status) returning * into v_event;
        exception when unique_violation then
          v_code := 'ID_CONFLICT'; v_message := '这条日程编号已存在，手机内容已保留，请另存为新日程。';
        end;
      elsif v_deleted then
        update public.events set deleted_at=now(),updated_at=now(),version=version+1
          where id=v_id and user_id=v_user returning * into v_event;
      else
        update public.events set title=v_title,start_at=v_start,end_at=v_end,status=v_status,updated_at=now(),version=version+1
          where id=v_id and user_id=v_user returning * into v_event;
      end if;
    end if;
    if v_code is null then
      v_result := jsonb_build_object('mutation_id',v_mutation,'status','applied','event',to_jsonb(v_event)-'user_id');
    else
      v_result := jsonb_build_object('mutation_id',v_mutation,'status','conflict','code',v_code,'message',v_message);
      if v_has_row then v_result := v_result || jsonb_build_object('event',to_jsonb(v_event)-'user_id'); end if;
    end if;
    insert into public.sync_mutations(user_id,mutation_id,payload_hash,result) values(v_user,v_mutation,v_hash,v_result);
    v_results := v_results || jsonb_build_array(v_result);
  end loop;
  select coalesce(jsonb_agg(to_jsonb(e)-'user_id' order by e.id), '[]'::jsonb) into v_events
    from (select * from public.events where user_id=v_user and (p_after is null or id>p_after) order by id limit 200) e;
  select exists(select 1 from public.events where user_id=v_user and (p_after is null or id>p_after) order by id offset 200 limit 1) into v_more;
  return jsonb_build_object('results',v_results,'events',v_events,'has_more',v_more,
    'next_cursor',case when v_more then v_events->199->>'id' else null end);
end;
$$;
revoke all on function public.sync_events(uuid,jsonb,uuid) from public, anon, authenticated;
grant execute on function public.sync_events(uuid,jsonb,uuid) to authenticated;

-- Existing imported overlaps must not prevent changing only a title.
create or replace function public.mutate_event(p_id uuid,p_base_version integer,p_patch jsonb,p_delete boolean default false)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  v_event public.events%rowtype;
  v_title text;
  v_start timestamptz;
  v_end timestamptz;
  v_status text;
begin
  if v_user is null then raise exception 'UNAUTHORIZED' using errcode='42501'; end if;
  if p_id is null or p_base_version is null or p_base_version < 1 or p_delete is null
     or jsonb_typeof(p_patch) is distinct from 'object' then raise exception 'VALIDATION_ERROR'; end if;
  if p_patch-array['title','start_local','end_local','status'] <> '{}'::jsonb
     or (not p_delete and p_patch='{}'::jsonb) or (p_delete and p_patch<>'{}'::jsonb)
     or exists(select 1 from jsonb_each(p_patch) a where jsonb_typeof(a.value)<>'string') then
    raise exception 'VALIDATION_ERROR';
  end if;
  perform pg_advisory_xact_lock(hashtextextended(v_user::text,0));
  select * into v_event from public.events where id=p_id and user_id=v_user for update;
  if not found then raise exception 'NOT_FOUND'; end if;
  if v_event.deleted_at is not null and p_delete and p_base_version in(v_event.version,v_event.version-1) then
    return jsonb_build_object('event',to_jsonb(v_event)-'user_id');
  end if;
  if v_event.deleted_at is not null then raise exception 'NOT_FOUND'; end if;
  if v_event.version <> p_base_version then raise exception 'VERSION_CONFLICT'; end if;
  v_title := case when p_patch ? 'title' then btrim(p_patch->>'title') else v_event.title end;
  v_start := case when p_patch ? 'start_local' then public._ssp_local_time(p_patch->>'start_local') else v_event.start_at end;
  v_end := case when p_patch ? 'end_local' then public._ssp_local_time(p_patch->>'end_local') else v_event.end_at end;
  v_status := coalesce(p_patch->>'status',v_event.status);
  if char_length(v_title) not between 1 and 120 or v_end<=v_start or v_end-v_start>interval '24 hours'
     or v_status not in('planned','completed') then raise exception 'VALIDATION_ERROR'; end if;
  if not p_delete and v_status='planned'
     and (v_event.status<>'planned' or v_start<>v_event.start_at or v_end<>v_event.end_at)
     and exists(select 1 from public.events e where e.user_id=v_user and e.id<>p_id and e.deleted_at is null
       and e.status='planned' and e.start_at<v_end and v_start<e.end_at) then
    raise exception 'SCHEDULE_CONFLICT';
  end if;
  update public.events set title=v_title,start_at=v_start,end_at=v_end,status=v_status,version=version+1,
    updated_at=now(),deleted_at=case when p_delete then now() else null end
    where id=p_id and user_id=v_user returning * into v_event;
  return jsonb_build_object('event',to_jsonb(v_event)-'user_id');
end;
$$;
