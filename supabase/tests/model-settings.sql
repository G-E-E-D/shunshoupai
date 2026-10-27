-- Run after both migrations, only in a disposable development database.
begin;
insert into auth.users(id,email) values
  ('33333333-3333-4333-8333-333333333333','model-a@example.invalid'),
  ('44444444-4444-4444-8444-444444444444','model-b@example.invalid');
set local role authenticated;
select set_config('request.jwt.claim.sub','33333333-3333-4333-8333-333333333333',true);
select set_config('request.jwt.claims','{"sub":"33333333-3333-4333-8333-333333333333","role":"authenticated"}',true);

do $$
declare
  v_cipher text := 'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB.encryptedOnly';
  v_saved jsonb;
  v_deleted jsonb;
  v_failed boolean;
begin
  if public.get_model_setting() is not null then raise exception 'FAIL: new account has model settings'; end if;
  v_saved := public.save_model_setting(0,'https://model.example/v1','example-model',v_cipher,'••••only');
  if (v_saved->>'version')::integer <> 1 or v_saved->>'api_key_ciphertext' <> v_cipher
      or v_saved ? 'user_id' then raise exception 'FAIL: initial encrypted record'; end if;
  if public.get_model_setting() <> v_saved then raise exception 'FAIL: saved record lookup'; end if;

  v_failed := false;
  begin perform public.save_model_setting(0,'https://other.example/v1','other',v_cipher,'••••only');
  exception when raise_exception then
    if sqlerrm not like 'VERSION_CONFLICT%' then raise; end if;
    v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: stale settings overwritten'; end if;

  v_failed := false;
  begin perform count(*) from public.model_settings;
  exception when insufficient_privilege then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: direct browser read of encrypted model table'; end if;
  v_failed := false;
  begin update public.model_settings set model='bypass' where user_id=auth.uid();
  exception when insufficient_privilege then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: direct browser write bypassed version checks'; end if;

  perform set_config('request.jwt.claim.sub','44444444-4444-4444-8444-444444444444',true);
  perform set_config('request.jwt.claims','{"sub":"44444444-4444-4444-8444-444444444444","role":"authenticated"}',true);
  if public.get_model_setting() is not null then raise exception 'FAIL: cross-account model lookup'; end if;
  if public.delete_model_setting(0) is not null then raise exception 'FAIL: deletion touched another account'; end if;
  perform public.save_model_setting(0,'https://b.example/v1','b-model',v_cipher,'••••only');

  perform set_config('request.jwt.claim.sub','33333333-3333-4333-8333-333333333333',true);
  perform set_config('request.jwt.claims','{"sub":"33333333-3333-4333-8333-333333333333","role":"authenticated"}',true);
  if public.get_model_setting()->>'model' <> 'example-model' then raise exception 'FAIL: another account changed A'; end if;
  v_saved := public.save_model_setting(1,'https://updated.example/v1','updated-model',v_cipher,'••••only');
  if (v_saved->>'version')::integer <> 2 then raise exception 'FAIL: update version'; end if;
  v_failed := false;
  begin perform public.delete_model_setting(1);
  exception when raise_exception then
    if sqlerrm not like 'VERSION_CONFLICT%' then raise; end if;
    v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: stale delete'; end if;

  v_deleted := public.delete_model_setting(2);
  if (v_deleted->>'version')::integer <> 3 or v_deleted->>'deleted_at' is null
      or v_deleted->>'base_url' is not null or v_deleted->>'api_key_ciphertext' is not null
      or v_deleted->>'key_hint' is not null then raise exception 'FAIL: delete did not erase credential ciphertext'; end if;
  if public.delete_model_setting(2) <> v_deleted then raise exception 'FAIL: delete retry'; end if;
  v_failed := false;
  begin perform public.save_model_setting(0,'https://old.example/v1','old',v_cipher,'••••only');
  exception when raise_exception then
    if sqlerrm not like 'VERSION_CONFLICT%' then raise; end if;
    v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: deleted settings recreated by stale tab'; end if;
  v_saved := public.save_model_setting(3,'https://new.example/v1','new',v_cipher,'••••only');
  if (v_saved->>'version')::integer <> 4 or v_saved->>'deleted_at' is not null then
    raise exception 'FAIL: monotonic version through recreation';
  end if;

  v_failed := false;
  begin perform public.save_model_setting(4,'https://new.example/v1','new','plaintext-key','已保存');
  exception when invalid_parameter_value then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: plaintext model key accepted'; end if;
  v_failed := false;
  begin perform public.save_model_setting(4,'https://user:password@example.com','new',v_cipher,'••••only');
  exception when invalid_parameter_value then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: embedded credential URL accepted'; end if;

  v_failed := false;
  begin perform public.save_model_setting(4,'https://new.example/v1','new',v_cipher,'only');
  exception when invalid_parameter_value then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: unmasked key hint accepted'; end if;
  v_saved := public.save_model_setting(4,'https://new.example/v1','short-key',
    'v1.AAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB.YQ','已保存');
  if (v_saved->>'version')::integer <> 5 then raise exception 'FAIL: short encrypted credential envelope'; end if;
end;
$$;

set local role anon;
select set_config('request.jwt.claim.sub','',true);
select set_config('request.jwt.claims','{}',true);
do $$
declare v_failed boolean := false;
begin
  begin perform public.get_model_setting();
  exception when insufficient_privilege then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: anonymous credential access'; end if;
  v_failed := false;
  begin perform public.delete_model_setting(0);
  exception when insufficient_privilege then v_failed := true;
  end;
  if not v_failed then raise exception 'FAIL: anonymous model mutation'; end if;
end;
$$;
rollback;
