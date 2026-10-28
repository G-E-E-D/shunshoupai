package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class LocalDraftCommitterInstrumentedTest {
 private Context context;private LocalScheduleStore store;private String filename,scope;
 @Before public void open(){context=ApplicationProvider.getApplicationContext();filename="draft-batch-"+UUID.randomUUID()+".sqlite";store=new LocalScheduleStore(context,filename);store.initialize("");scope=store.primaryScope();}
 @After public void close(){if(store!=null)store.close();if(context!=null)context.deleteDatabase(filename);}
 private JSONObject draft(String title,int hour)throws Exception{return new JSONObject().put("id",UUID.randomUUID().toString()).put("title",title).put("start_local",String.format(java.util.Locale.ROOT,"2033-01-01T%02d:00:00",hour)).put("end_local",String.format(java.util.Locale.ROOT,"2033-01-01T%02d:00:00",hour+1)).put("timezone","Asia/Shanghai").put("status","planned").put("deleted",false);}
 private JSONObject payload(JSONObject draft)throws Exception{JSONObject value=new JSONObject(draft.toString());value.remove("id");return value;}
 private void denied(String code,JSONArray drafts)throws Exception{try{store.commitDrafts(scope,drafts);fail("expected "+code);}catch(AncestryReplica.Invalid error){assertEquals(code,error.code);}}
 @Test public void confirmedBatchCreatesLocalReplicasWithoutPairingAndSurvivesRestart()throws Exception{
  JSONArray batch=new JSONArray().put(draft("一",9)).put(draft("二",11));JSONArray ids=store.commitDrafts(scope,batch);assertEquals(2,ids.length());assertEquals(0,store.peers().size());assertEquals(2,store.replica().page(null).getJSONArray("records").length());
  store.close();store=new LocalScheduleStore(context,filename);assertEquals(ids.toString(),store.commitDrafts(scope,batch).toString());assertEquals(2,store.list(scope).size());
 }
 @Test public void retryNeverRevertsSubsequentEditsOrResurrectsDeletion()throws Exception{
  JSONObject initial=draft("原稿",9);JSONArray batch=new JSONArray().put(initial);store.commitDrafts(scope,batch);String id=initial.getString("id");
  JSONObject edit=payload(initial).put("title","之后编辑");store.save(scope,id,edit,store.list(scope).get(0).getInt("version"));store.commitDrafts(scope,batch);assertEquals("之后编辑",store.list(scope).get(0).getString("title"));
  store.save(scope,id,new JSONObject(edit.toString()).put("deleted",true),store.list(scope).get(0).getInt("version"));int version=store.list(scope).get(0).getInt("version");store.commitDrafts(scope,batch);assertTrue(store.list(scope).get(0).getBoolean("deleted"));assertEquals(version,store.list(scope).get(0).getInt("version"));
 }
 @Test public void conflictInLastDraftRollsBackEarlierRowsRevisionsAndReceipts()throws Exception{
  JSONObject first=draft("first",9),second=draft("overlap",9);denied("TIME_CONFLICT",new JSONArray().put(first).put(second));assertEquals(0,store.list(scope).size());assertEquals(0,store.replica().page(null).getJSONArray("records").length());
  assertEquals(1,store.commitDrafts(scope,new JSONArray().put(first)).length());assertEquals(1,store.list(scope).size());
 }
 @Test public void overlapWithExistingAppointmentIsRejectedAndCompletionReleasesTheSlot()throws Exception{
  JSONObject old=draft("已有",9);String id=store.save(scope,null,payload(old));denied("TIME_CONFLICT",new JSONArray().put(draft("冲突",9)));assertEquals(1,store.list(scope).size());
  store.save(scope,id,payload(old).put("status","completed"),store.list(scope).get(0).getInt("version"));store.commitDrafts(scope,new JSONArray().put(draft("安排",9)));assertEquals(2,store.list(scope).size());
 }
 @Test public void duplicateChangedAndMalformedDraftsDoNotPartlySave()throws Exception{
  JSONObject item=draft("原稿",9);denied("ID_CONFLICT",new JSONArray().put(item).put(item));assertEquals(0,store.list(scope).size());
  store.commitDrafts(scope,new JSONArray().put(item));denied("IDEMPOTENCY_CONFLICT",new JSONArray().put(new JSONObject(item.toString()).put("title","变了")));assertEquals("原稿",store.list(scope).get(0).getString("title"));
  JSONArray invalid=new JSONArray().put(draft("valid",12)).put(draft("invalid",14).put("owner","other"));denied("VALIDATION_ERROR",invalid);assertEquals(1,store.list(scope).size());
 }
 @Test public void emptyOversizedAndArchivedBatchesCannotWrite()throws Exception{
  denied("VALIDATION_ERROR",new JSONArray());JSONArray many=new JSONArray();for(int i=0;i<21;i++)many.put(draft("more",9));denied("VALIDATION_ERROR",many);
  try{store.commitDrafts("legacy:separate",new JSONArray().put(draft("归档",9)));fail("archive write");}catch(AncestryReplica.Invalid expected){assertEquals("READ_ONLY_ARCHIVE",expected.code);}assertEquals(0,store.list(scope).size());
 }
}
