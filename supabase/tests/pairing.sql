-- Disposable PostgreSQL/Supabase boundary checks. Never run on production data.
begin;
insert into auth.users(id,email) values
 ('55555555-5555-4555-8555-555555555555','pair-a@example.invalid'),
 ('66666666-6666-4666-8666-666666666666','pair-b@example.invalid');
insert into auth.sessions(id,user_id,not_after) values
 ('aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa','55555555-5555-4555-8555-555555555555',null),
 ('aaaaaaa2-aaaa-4aaa-8aaa-aaaaaaaaaaaa','55555555-5555-4555-8555-555555555555',null),
 ('bbbbbbb1-bbbb-4bbb-8bbb-bbbbbbbbbbbb','66666666-6666-4666-8666-666666666666',null);
set local role authenticated;
select set_config('request.jwt.claim.sub','55555555-5555-4555-8555-555555555555',true);
select set_config('request.jwt.claims',jsonb_build_object('sub','55555555-5555-4555-8555-555555555555',
 'session_id','aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa','exp',floor(extract(epoch from now()+interval '1 hour'))::bigint)::text,true);
do $$
declare
 v_cipher text := 'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB.encryptedAccessJwtOnly';
 v_record jsonb;
 v_failed boolean;
begin
 v_record := public.create_pairing_request(repeat('1',64),v_cipher);
 if v_record->>'user_id' <> auth.uid()::text or v_record ? 'auth_ciphertext'
   or (v_record->>'expires_at')::timestamptz > clock_timestamp()+interval '90 seconds'
   or (v_record->>'expires_at')::timestamptz <= clock_timestamp()
   or (v_record->>'auth_expires_at')::timestamptz < clock_timestamp()+interval '59 minutes' then
   raise exception 'FAIL: owner/90-second TTL/access expiry contract';
 end if;
 v_failed := false;
 begin perform count(*) from public.pairing_requests;
 exception when insufficient_privilege then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: browser can enumerate pairing authorizations'; end if;
 v_failed := false;
 begin delete from public.pairing_requests;
 exception when insufficient_privilege then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: direct pairing writes allowed'; end if;
 v_failed := false;
 begin perform public.create_pairing_request(repeat('2',64),'plaintext-access-token');
 exception when invalid_parameter_value then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: plaintext session material accepted'; end if;
 -- A signed-in user cannot choose another account's source session.
 perform set_config('request.jwt.claims',jsonb_build_object('session_id','bbbbbbb1-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
   'exp',floor(extract(epoch from now()+interval '1 hour'))::bigint)::text,true);
 v_failed := false;
 begin perform public.create_pairing_request(repeat('2',64),v_cipher);
 exception when invalid_parameter_value then
   if sqlerrm <> 'PAIRING_UNAVAILABLE' then raise; end if; v_failed := true;
 end;
 if not v_failed then raise exception 'FAIL: cross-account source session accepted'; end if;
 perform set_config('request.jwt.claims',jsonb_build_object('session_id','aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
   'exp',floor(extract(epoch from now()-interval '1 second'))::bigint)::text,true);
 v_failed := false;
 begin perform public.create_pairing_request(repeat('2',64),v_cipher);
 exception when invalid_parameter_value then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: expired JWT can issue a QR'; end if;
 perform set_config('request.jwt.claims',jsonb_build_object('exp',floor(extract(epoch from now()+interval '1 hour'))::bigint)::text,true);
 v_failed := false;
 begin perform public.create_pairing_request(repeat('2',64),v_cipher);
 exception when invalid_parameter_value then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: missing source session can issue a QR'; end if;
end;
$$;
set local role anon;
select set_config('request.jwt.claim.sub','',true);
select set_config('request.jwt.claims','{}',true);
do $$
declare v_record jsonb; v_failed boolean;
begin
 v_record := public.consume_pairing_request(repeat('1',64));
 if v_record->>'user_id' <> '55555555-5555-4555-8555-555555555555'
   or v_record->>'auth_ciphertext' <> 'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB.encryptedAccessJwtOnly'
   or v_record ? 'source_session_id' then raise exception 'FAIL: anonymous opaque exchange contract'; end if;
 v_failed := false;
 begin perform public.consume_pairing_request(repeat('1',64));
 exception when invalid_parameter_value then
   if sqlerrm <> 'PAIRING_INVALID' then raise; end if; v_failed := true;
 end;
 if not v_failed then raise exception 'FAIL: pairing token replay'; end if;
 v_failed := false;
 begin perform public.consume_pairing_request('not-a-sha256-hash');
 exception when invalid_parameter_value then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: malformed hash accepted'; end if;
 v_failed := false;
 begin perform public.create_pairing_request(repeat('2',64),'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB.encrypted');
 exception when insufficient_privilege then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: anonymous issuance'; end if;
 v_failed := false;
 begin perform count(*) from public.pairing_requests;
 exception when insufficient_privilege then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: anonymous enumeration'; end if;
end;
$$;
set local role authenticated;
select set_config('request.jwt.claim.sub','55555555-5555-4555-8555-555555555555',true);
select set_config('request.jwt.claims',jsonb_build_object('session_id','aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
 'exp',floor(extract(epoch from now()+interval '1 hour'))::bigint)::text,true);
select public.create_pairing_request(repeat('2',64),'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB.encrypted');
select public.create_pairing_request(repeat('3',64),'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB.encrypted');
do $$
declare v_failed boolean := false;
begin
 begin perform public.consume_pairing_request(repeat('2',64));
 exception when invalid_parameter_value then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: regenerated QR did not invalidate its predecessor'; end if;
end;
$$;
-- Owner session revocation blocks the still-unconsumed latest QR.
reset role;
delete from auth.sessions where id='aaaaaaa1-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
set local role anon;
do $$
declare v_failed boolean := false;
begin
 begin perform public.consume_pairing_request(repeat('3',64));
 exception when invalid_parameter_value then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: revoked source still authorizes a device'; end if;
end;
$$;
reset role;
update auth.sessions set not_after = clock_timestamp()+interval '30 seconds'
 where id='aaaaaaa2-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
set local role authenticated;
select set_config('request.jwt.claims',jsonb_build_object('session_id','aaaaaaa2-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
 'exp',floor(extract(epoch from now()+interval '1 hour'))::bigint)::text,true);
do $$
declare v_record jsonb;
begin
 v_record := public.create_pairing_request(repeat('4',64),'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB.encrypted');
 if (v_record->>'auth_expires_at')::timestamptz > clock_timestamp()+interval '30 seconds'
   or v_record->>'expires_at' <> v_record->>'auth_expires_at' then
   raise exception 'FAIL: source session deadline did not cap QR and delegated authorization'; end if;
end;
$$;
reset role;
update auth.sessions set not_after = clock_timestamp()-interval '1 second'
 where id='aaaaaaa2-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
set local role anon;
do $$
declare v_failed boolean := false;
begin
 begin perform public.consume_pairing_request(repeat('4',64));
 exception when invalid_parameter_value then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: source session deadline was not checked again'; end if;
end;
$$;
reset role;
update auth.sessions set not_after = null where id='aaaaaaa2-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
set local role authenticated;
select set_config('request.jwt.claims',jsonb_build_object('session_id','aaaaaaa2-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
 'exp',floor(extract(epoch from now()+interval '20 seconds'))::bigint)::text,true);
do $$
declare v_record jsonb;
begin
 v_record := public.create_pairing_request(repeat('5',64),'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB.encrypted');
 if (v_record->>'auth_expires_at')::timestamptz > clock_timestamp()+interval '20 seconds'
   or v_record->>'expires_at' <> v_record->>'auth_expires_at' then
   raise exception 'FAIL: JWT expiry did not cap pairing'; end if;
end;
$$;
reset role;
update public.pairing_requests set expires_at=clock_timestamp()-interval '1 second' where token_hash=repeat('5',64);
set local role anon;
do $$
declare v_failed boolean := false;
begin
 begin perform public.consume_pairing_request(repeat('5',64));
 exception when invalid_parameter_value then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: expired QR accepted'; end if;
end;
$$;
rollback;
