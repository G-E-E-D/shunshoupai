-- Run only in a disposable database after all migrations; rolls back fixtures.
begin;
insert into auth.users(id,email) values
 ('77777777-7777-4777-8777-777777777777','sync-a@example.invalid'),
 ('88888888-8888-4888-8888-888888888888','sync-b@example.invalid');
set local role authenticated;
select set_config('request.jwt.claim.sub','77777777-7777-4777-8777-777777777777',true);
do $$
declare
 v_item jsonb := '{"mutation_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1","id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2","base_version":0,"deleted":false,"event":{"title":"手机离线","start_local":"2030-10-28T09:00:00","end_local":"2030-10-28T10:00:00","timezone":"Asia/Shanghai","status":"planned"}}';
 v_result jsonb;
 v_replay jsonb;
 v_failed boolean;
begin
 perform public.commit_events('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3',
  '[{"title":"电脑安排","start_local":"2030-10-28T09:00:00","end_local":"2030-10-28T10:00:00","timezone":"Asia/Shanghai","time_source":"explicit","assumptions":[]}]');
 v_result := public.sync_events(auth.uid(),jsonb_build_array(v_item));
 if jsonb_array_length(v_result->'events')<>2 or v_result->'results'->0->>'status'<>'applied' then
   raise exception 'FAIL: merge overlapping commitments';
 end if;
 v_replay := public.sync_events(auth.uid(),jsonb_build_array(v_item));
 if v_replay<>v_result then raise exception 'FAIL: replay'; end if;
 v_failed := false;
 begin perform public.sync_events(auth.uid(),jsonb_build_array(jsonb_set(v_item,'{event,title}','"changed"')));
 exception when raise_exception then
   if sqlerrm not like 'IDEMPOTENCY_CONFLICT%' then raise; end if;
   v_failed := true;
 end;
 if not v_failed then raise exception 'FAIL: changed replay'; end if;
 perform public.mutate_event((v_item->>'id')::uuid,1,'{"title":"电脑改标题"}');
 v_item := jsonb_set(jsonb_set(v_item,'{mutation_id}','"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4"'),'{base_version}','1');
 v_result := public.sync_events(auth.uid(),jsonb_build_array(v_item));
 if v_result->'results'->0->>'code'<>'VERSION_CONFLICT' or v_result->'results'->0->'event'->>'title'<>'电脑改标题' then
   raise exception 'FAIL: stale overwrite';
 end if;
 v_item := jsonb_build_object('mutation_id','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5','id',v_item->>'id','base_version',2,'deleted',true);
 v_result := public.sync_events(auth.uid(),jsonb_build_array(v_item));
 if v_result->'results'->0->'event'->>'deleted_at' is null then raise exception 'FAIL: deletion tombstone'; end if;
 if public.sync_events(auth.uid(),jsonb_build_array(v_item))<>v_result then raise exception 'FAIL: delete retry'; end if;
 v_failed := false;
 begin perform count(*) from public.sync_mutations;
 exception when insufficient_privilege then v_failed := true; end;
 if not v_failed then raise exception 'FAIL: receipt direct access'; end if;
end;
$$;
select set_config('request.jwt.claim.sub','88888888-8888-4888-8888-888888888888',true);
do $$
declare v_result jsonb; v_failed boolean := false;
begin
 v_result := public.sync_events(auth.uid(),'[{"mutation_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1","id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2","base_version":1,"deleted":true}]');
 if jsonb_array_length(v_result->'events')<>0 or v_result->'results'->0 ? 'event' or v_result->'results'->0->>'code'<>'NOT_FOUND' then
   raise exception 'FAIL: cross account disclosure';
 end if;
 begin perform public.sync_events('77777777-7777-4777-8777-777777777777','[]');
 exception when raise_exception then
   if sqlerrm not like 'ACCOUNT_MISMATCH%' then raise; end if;
   v_failed := true;
 end;
 if not v_failed then raise exception 'FAIL: changed account accepted'; end if;
end;
$$;
rollback;
