-- Personal model credentials are AES-GCM ciphertext produced by the application.
-- The encryption master key belongs to the Node.js service, never PostgreSQL or
-- the browser. Run after 20261027000000_initial.sql, including on existing sites.
create table public.model_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  base_url text,
  model text,
  api_key_ciphertext text,
  key_hint text,
  version integer not null default 1 check (version >= 1),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  check (
    (deleted_at is null and base_url is not null and model is not null
      and api_key_ciphertext is not null and key_hint is not null
      and char_length(base_url) between 1 and 2048
      and char_length(model) between 1 and 200
      and char_length(api_key_ciphertext) between 45 and 16384
      and char_length(key_hint) between 1 and 8)
    or
    (deleted_at is not null and base_url is null and model is null
      and api_key_ciphertext is null and key_hint is null)
  )
);

alter table public.model_settings enable row level security;
create policy model_settings_read_own on public.model_settings
  for select to authenticated using (user_id = (select auth.uid()));
-- Even the owner's browser has no direct table access. Restricted functions
-- return only that owner's opaque ciphertext to the authenticated server client.
revoke all on public.model_settings from public, anon, authenticated;

create function public.get_model_setting() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  v_record public.model_settings%rowtype;
begin
  if v_user is null then
    raise exception 'UNAUTHORIZED' using errcode = '42501';
  end if;
  select * into v_record from public.model_settings where user_id = v_user;
  if not found then return null; end if;
  return to_jsonb(v_record) - 'user_id';
end;
$$;

create function public.save_model_setting(
  p_base_version integer,
  p_base_url text,
  p_model text,
  p_api_key_ciphertext text,
  p_key_hint text
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  v_record public.model_settings%rowtype;
begin
  if v_user is null then
    raise exception 'UNAUTHORIZED' using errcode = '42501';
  end if;
  if p_base_version is null or p_base_version < 0
     or p_base_url is null or char_length(p_base_url) not between 1 and 2048
     or p_base_url <> btrim(p_base_url)
     or p_base_url !~ '^https?://[^[:space:]?#@]+$'
     or p_model is null or char_length(p_model) not between 1 and 200
     or p_model <> btrim(p_model) or p_model ~ '[[:cntrl:]]'
     or p_api_key_ciphertext is null or char_length(p_api_key_ciphertext) > 16384
     or p_api_key_ciphertext !~ '^v1\.[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]+$'
     or p_key_hint is null or p_key_hint !~ '^(••••[!-~]{4}|已保存)$' then
    raise exception 'VALIDATION_ERROR: invalid model configuration' using errcode = '22023';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('model_settings:' || v_user::text, 0));
  select * into v_record from public.model_settings where user_id = v_user for update;
  if not found then
    if p_base_version <> 0 then
      raise exception 'VERSION_CONFLICT: refresh model settings before saving';
    end if;
    insert into public.model_settings(user_id,base_url,model,api_key_ciphertext,key_hint)
      values(v_user,p_base_url,p_model,p_api_key_ciphertext,p_key_hint)
      returning * into v_record;
  else
    if v_record.version <> p_base_version then
      raise exception 'VERSION_CONFLICT: refresh model settings before saving';
    end if;
    update public.model_settings set base_url = p_base_url, model = p_model,
      api_key_ciphertext = p_api_key_ciphertext, key_hint = p_key_hint,
      version = version + 1, updated_at = now(), deleted_at = null
      where user_id = v_user returning * into v_record;
  end if;
  return to_jsonb(v_record) - 'user_id';
end;
$$;

create function public.delete_model_setting(p_base_version integer) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  v_record public.model_settings%rowtype;
begin
  if v_user is null then
    raise exception 'UNAUTHORIZED' using errcode = '42501';
  end if;
  if p_base_version is null or p_base_version < 0 then
    raise exception 'VALIDATION_ERROR: invalid model version' using errcode = '22023';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('model_settings:' || v_user::text, 0));
  select * into v_record from public.model_settings where user_id = v_user for update;
  if not found then
    if p_base_version <> 0 then
      raise exception 'VERSION_CONFLICT: refresh model settings before deleting';
    end if;
    return null;
  end if;
  -- Preserve a tombstone/version so a stale tab cannot recreate old credentials.
  -- A response-lost delete can be retried with its original base version.
  if v_record.deleted_at is not null
      and p_base_version in (v_record.version, v_record.version - 1) then
    return to_jsonb(v_record) - 'user_id';
  end if;
  if v_record.version <> p_base_version then
    raise exception 'VERSION_CONFLICT: refresh model settings before deleting';
  end if;
  update public.model_settings set base_url = null, model = null,
    api_key_ciphertext = null, key_hint = null, version = version + 1,
    updated_at = now(), deleted_at = now()
    where user_id = v_user returning * into v_record;
  return to_jsonb(v_record) - 'user_id';
end;
$$;

revoke all on function public.get_model_setting() from public, anon, authenticated;
revoke all on function public.save_model_setting(integer,text,text,text,text) from public, anon, authenticated;
revoke all on function public.delete_model_setting(integer) from public, anon, authenticated;
grant execute on function public.get_model_setting() to authenticated;
grant execute on function public.save_model_setting(integer,text,text,text,text) to authenticated;
grant execute on function public.delete_model_setting(integer) to authenticated;
