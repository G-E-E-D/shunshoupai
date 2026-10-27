-- Short-lived, single-use cross-device authorization. Only opaque AES-GCM
-- ciphertext of the issuing access JWT is stored; no refresh token is copied.
create table public.pairing_requests (
  token_hash text primary key check (token_hash ~ '^[0-9a-f]{64}$'),
  user_id uuid not null references auth.users(id) on delete cascade,
  source_session_id uuid not null,
  auth_ciphertext text not null check (
    char_length(auth_ciphertext) between 45 and 16384
    and auth_ciphertext ~ '^v1\.[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]+$'
  ),
  expires_at timestamptz not null,
  auth_expires_at timestamptz not null,
  consumed_at timestamptz,
  created_at timestamptz not null default now(),
  check (expires_at <= auth_expires_at)
);
create index pairing_requests_source_session on public.pairing_requests(source_session_id);
alter table public.pairing_requests enable row level security;
revoke all on public.pairing_requests from public, anon, authenticated;

create function public.create_pairing_request(p_token_hash text, p_auth_ciphertext text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_user uuid := auth.uid();
  v_claims jsonb := auth.jwt();
  v_session uuid;
  v_not_after timestamptz;
  v_auth_expires timestamptz;
  v_expires timestamptz;
  v_now timestamptz := clock_timestamp();
begin
  if v_user is null or p_token_hash is null or p_token_hash !~ '^[0-9a-f]{64}$'
      or p_auth_ciphertext is null or char_length(p_auth_ciphertext) > 16384
      or p_auth_ciphertext !~ '^v1\.[A-Za-z0-9_-]{16}\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]+$'
      or coalesce(v_claims->>'session_id','') !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
      or coalesce(v_claims->>'exp','') !~ '^[0-9]{1,12}$' then
    raise exception 'PAIRING_UNAVAILABLE' using errcode = '22023';
  end if;
  v_session := (v_claims->>'session_id')::uuid;
  v_auth_expires := to_timestamp((v_claims->>'exp')::double precision);
  select s.not_after into v_not_after from auth.sessions s
    where s.id = v_session and s.user_id = v_user;
  if not found or v_auth_expires <= v_now or (v_not_after is not null and v_not_after <= v_now) then
    raise exception 'PAIRING_UNAVAILABLE' using errcode = '22023';
  end if;
  if v_not_after is not null then v_auth_expires := least(v_auth_expires,v_not_after); end if;
  v_expires := least(v_now + interval '90 seconds',v_auth_expires);
  -- Regenerating a QR invalidates its predecessor, including concurrent issues.
  perform pg_advisory_xact_lock(hashtextextended('pairing:' || v_session::text,0));
  update public.pairing_requests set consumed_at = v_now
    where source_session_id = v_session and consumed_at is null;
  delete from public.pairing_requests where expires_at < v_now - interval '1 day';
  insert into public.pairing_requests(token_hash,user_id,source_session_id,auth_ciphertext,expires_at,auth_expires_at)
    values(p_token_hash,v_user,v_session,p_auth_ciphertext,v_expires,v_auth_expires);
  return jsonb_build_object('user_id',v_user,'expires_at',v_expires,'auth_expires_at',v_auth_expires);
exception when unique_violation then
  raise exception 'PAIRING_UNAVAILABLE' using errcode = '22023';
end;
$$;

create function public.consume_pairing_request(p_token_hash text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_record public.pairing_requests%rowtype;
  v_now timestamptz := clock_timestamp();
begin
  if p_token_hash is null or p_token_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'PAIRING_INVALID' using errcode = '22023';
  end if;
  -- UPDATE owns the row lock: simultaneous redemption can return this record
  -- exactly once. A revoked/expired source session cannot authorize a device.
  update public.pairing_requests p set consumed_at = v_now,
      expires_at = least(p.expires_at,coalesce(s.not_after,p.expires_at)),
      auth_expires_at = least(p.auth_expires_at,coalesce(s.not_after,p.auth_expires_at))
    from auth.sessions s
    where p.token_hash = p_token_hash and p.consumed_at is null
      and p.expires_at > v_now and p.auth_expires_at > v_now
      and s.id = p.source_session_id and s.user_id = p.user_id
      and (s.not_after is null or s.not_after > v_now)
    returning p.* into v_record;
  if not found then raise exception 'PAIRING_INVALID' using errcode = '22023'; end if;
  return jsonb_build_object('user_id',v_record.user_id,'auth_ciphertext',v_record.auth_ciphertext,
    'expires_at',v_record.expires_at,'auth_expires_at',v_record.auth_expires_at);
end;
$$;

revoke all on function public.create_pairing_request(text,text) from public, anon, authenticated;
revoke all on function public.consume_pairing_request(text) from public, anon, authenticated;
grant execute on function public.create_pairing_request(text,text) to authenticated;
grant execute on function public.consume_pairing_request(text) to anon, authenticated;
