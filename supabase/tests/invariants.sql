-- Run ONLY in a disposable development Supabase database, as postgres.
-- Everything, including the two auth fixtures, is rolled back at the end.
-- With psql: psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f supabase/tests/invariants.sql
begin;

insert into auth.users(id, email) values
  ('11111111-1111-4111-8111-111111111111', 'sql-test-a@example.invalid'),
  ('22222222-2222-4222-8222-222222222222', 'sql-test-b@example.invalid');

set local role authenticated;
select set_config('request.jwt.claim.sub', '11111111-1111-4111-8111-111111111111', true);
select set_config('request.jwt.claims', '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}', true);

do $$
declare
  v_drafts jsonb := '[{"title":"SQL test","start_local":"2029-10-27T09:00:00","end_local":"2029-10-27T10:00:00","timezone":"Asia/Shanghai","time_source":"explicit","assumptions":[]}]';
  v_request uuid := 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  v_result jsonb;
  v_id uuid;
  v_new_id uuid;
  v_version integer;
  v_failed boolean;
begin
  v_result := public.commit_events(v_request, v_drafts);
  v_id := (v_result->'events'->0->>'id')::uuid;
  if (v_result->>'replayed')::boolean or jsonb_array_length(v_result->'events') <> 1 then
    raise exception 'FAIL: initial commit result';
  end if;
  if (select count(*) from public.events) <> 1 then
    raise exception 'FAIL: owner select';
  end if;
  if not (public.commit_events(v_request, v_drafts)->>'replayed')::boolean
      or (select count(*) from public.events) <> 1 then
    raise exception 'FAIL: idempotency';
  end if;

  v_failed := false;
  begin
    perform public.commit_events(v_request, jsonb_set(v_drafts, '{0,title}', '"changed"'));
  exception when raise_exception then
    if sqlerrm not like 'IDEMPOTENCY_CONFLICT%' then raise; end if;
    v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: same key accepted different payload'; end if;

  v_failed := false;
  begin
    insert into public.events(user_id,title,start_at,end_at)
      values(auth.uid(), 'bypass', '2029-10-28 09:00+08', '2029-10-28 10:00+08');
  exception when insufficient_privilege then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: direct write bypassed RPC'; end if;

  v_failed := false;
  begin
    perform public.commit_events(gen_random_uuid(), v_drafts);
  exception when raise_exception then
    if sqlerrm not like 'SCHEDULE_CONFLICT%' then raise; end if;
    v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: overlapping event was accepted'; end if;

  -- A later conflicting item must roll back the earlier item in the same batch.
  v_failed := false;
  begin
    perform public.commit_events(gen_random_uuid(),
      jsonb_build_array(jsonb_set(jsonb_set(v_drafts->0, '{start_local}', '"2029-10-27T11:00:00"'), '{end_local}', '"2029-10-27T12:00:00"')) || v_drafts);
  exception when raise_exception then
    if sqlerrm not like 'SCHEDULE_CONFLICT%' then raise; end if;
    v_failed := true;
  end;
  if not v_failed or (select count(*) from public.events) <> 1 then
    raise exception 'FAIL: batch was not atomic';
  end if;

  v_result := public.mutate_event(v_id, 1, '{"title":"updated"}');
  if (v_result->'event'->>'version')::integer <> 2 then raise exception 'FAIL: version not incremented'; end if;
  v_failed := false;
  begin
    perform public.mutate_event(v_id, 1, '{"title":"stale"}');
  exception when raise_exception then
    if sqlerrm not like 'VERSION_CONFLICT%' then raise; end if;
    v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: stale edit accepted'; end if;

  -- Same session, different JWT identity: both SELECT and mutation must isolate.
  perform set_config('request.jwt.claim.sub', '22222222-2222-4222-8222-222222222222', true);
  perform set_config('request.jwt.claims', '{"sub":"22222222-2222-4222-8222-222222222222","role":"authenticated"}', true);
  if (select count(*) from public.events) <> 0 then raise exception 'FAIL: cross-account SELECT'; end if;
  v_failed := false;
  begin
    perform public.mutate_event(v_id, 2, '{"title":"intrusion"}');
  exception when raise_exception then
    if sqlerrm not like 'NOT_FOUND%' then raise; end if;
    v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: cross-account edit'; end if;

  perform set_config('request.jwt.claim.sub', '11111111-1111-4111-8111-111111111111', true);
  perform set_config('request.jwt.claims', '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated"}', true);
  v_result := public.mutate_event(v_id, 2, '{}'::jsonb, true);
  v_version := (v_result->'event'->>'version')::integer;
  if v_result->'event'->>'deleted_at' is null or v_version <> 3 then raise exception 'FAIL: soft deletion'; end if;
  if public.mutate_event(v_id, 2, '{}'::jsonb, true) <> v_result then raise exception 'FAIL: DELETE retry'; end if;

  -- Half-open intervals accept adjacency. Completed items release their slot,
  -- but cannot become planned again if another event has occupied that slot.
  v_result := public.commit_events(gen_random_uuid(), v_drafts);
  v_new_id := (v_result->'events'->0->>'id')::uuid;
  perform public.commit_events(gen_random_uuid(),
    jsonb_set(jsonb_set(v_drafts, '{0,start_local}', '"2029-10-27T10:00:00"'), '{0,end_local}', '"2029-10-27T11:00:00"'));
  perform public.mutate_event(v_new_id, 1, '{"status":"completed"}');
  perform public.commit_events(gen_random_uuid(), v_drafts);
  v_failed := false;
  begin
    perform public.mutate_event(v_new_id, 2, '{"status":"planned"}');
  exception when raise_exception then
    if sqlerrm not like 'SCHEDULE_CONFLICT%' then raise; end if;
    v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: completed-to-planned overlap'; end if;

  v_failed := false;
  begin
    perform public.commit_events(gen_random_uuid(),
      jsonb_set(jsonb_set(v_drafts, '{0,start_local}', '"2029-02-30T09:00:00"'), '{0,end_local}', '"2029-02-30T10:00:00"'));
  exception when invalid_parameter_value then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: invalid calendar date'; end if;

  if not public.consume_ai_quota(2) or not public.consume_ai_quota(2) or public.consume_ai_quota(2) then
    raise exception 'FAIL: quota';
  end if;
end;
$$;

set local role anon;
select set_config('request.jwt.claim.sub', '', true);
select set_config('request.jwt.claims', '{}', true);
do $$
declare v_failed boolean := false;
begin
  begin
    perform public.commit_events(gen_random_uuid(), '[]'::jsonb);
  exception when insufficient_privilege then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: anonymous write access'; end if;
  v_failed := false;
  begin
    perform count(*) from public.events;
  exception when insufficient_privilege then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: anonymous read access'; end if;
end;
$$;

rollback;
