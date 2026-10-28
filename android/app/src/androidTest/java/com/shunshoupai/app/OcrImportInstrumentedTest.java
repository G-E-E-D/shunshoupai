package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class OcrImportInstrumentedTest {
    private Context app,isolated;
    private LocalScheduleStore store;
    private LocalPlanningEngine engine;
    private String database,namespace,scope;
    private ActivityScenario<MainActivity> scenario;
    private NativePlanningDialog planner;
    private OcrTextInbox inbox;
    @Before public void open() {
        app=ApplicationProvider.getApplicationContext();namespace="ocr-import-"+UUID.randomUUID()+"-";database=namespace+"calendar.sqlite";
        store=new LocalScheduleStore(app,database);store.initialize("");scope=store.primaryScope();engine=new LocalPlanningEngine(app,namespace+"model");
    }
    @After public void close() {
        if(scenario!=null){scenario.onActivity(activity->{if(planner!=null)planner.close();});scenario.close();}
        engine.cancel();store.close();app.deleteDatabase(database);
        for(String suffix:new String[]{"ocr-text-inbox-v1","planning-drafts","model","queue"})app.getSharedPreferences(namespace+suffix,Context.MODE_PRIVATE).edit().clear().commit();
    }
    private void show(JSONObject initial) {
        scenario=ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity->{
            isolated=new ContextWrapper(activity){@Override public SharedPreferences getSharedPreferences(String name,int mode){return app.getSharedPreferences(namespace+name,mode);}};
            if(initial!=null)isolated.getSharedPreferences("planning-drafts",Context.MODE_PRIVATE).edit().putString(scope,initial.toString()).commit();
            inbox=new OcrTextInbox(isolated);planner=new NativePlanningDialog(isolated,store,engine,scope,LocalDate.of(2026,10,28),ignored->{});planner.show();
        });
    }
    private Object field(String name){try{java.lang.reflect.Field field=NativePlanningDialog.class.getDeclaredField(name);field.setAccessible(true);return field.get(planner);}catch(Exception error){throw new AssertionError(error);}}
    private String input(){AtomicReference<String> value=new AtomicReference<>();scenario.onActivity(activity->value.set(((EditText)((AlertDialog)field("dialog")).findViewById(R.id.planner_input)).getText().toString()));return value.get();}
    private JSONObject state()throws Exception{return new JSONObject(isolated.getSharedPreferences("planning-drafts",Context.MODE_PRIVATE).getString(scope,"{}"));}
    private void importText(String id,boolean organize){scenario.onActivity(activity->planner.importRecognizedText(id,organize));}
    private void choice(int button){scenario.onActivity(activity->{AlertDialog prompt=(AlertDialog)field("ocrPrompt");assertNotNull("Existing work needs an explicit choice",prompt);prompt.getButton(button).performClick();});}
    private JSONObject original(String text)throws Exception {
        JSONObject draft=new JSONObject().put("id",UUID.randomUUID().toString()).put("title","原有未确认草稿").put("start_local","2026-10-29T15:00:00").put("end_local","2026-10-29T16:00:00").put("timezone","Asia/Shanghai").put("status","planned").put("deleted",false).put("selected",true);
        return new JSONObject().put("text",text).put("drafts",new JSONArray().put(draft)).put("source","rules");
    }

    @Test public void appendRequiresAChoiceAndKeepsExistingDraftsWithoutParsingOrCommitting()throws Exception {
        JSONObject original=original("原来的输入");show(original);String id=inbox.offer(scope,"明天上午9点开会一小时");String beforeDrafts=original.getJSONArray("drafts").toString();
        importText(id,true);assertEquals("原来的输入",input());assertEquals(beforeDrafts,state().getJSONArray("drafts").toString());assertTrue(store.list(scope).isEmpty());
        choice(AlertDialog.BUTTON_NEGATIVE);assertNotNull(inbox.get(id));assertEquals("原来的输入",input());
        importText(id,true);choice(AlertDialog.BUTTON_POSITIVE);assertEquals("原来的输入\n\n明天上午9点开会一小时",input());assertEquals(beforeDrafts,state().getJSONArray("drafts").toString());assertNull(inbox.get(id));assertTrue(store.list(scope).isEmpty());
        importText(id,true);assertEquals("原来的输入\n\n明天上午9点开会一小时",input());assertEquals(beforeDrafts,state().getJSONArray("drafts").toString());
    }

    @Test public void explicitScreenshotConfirmationGeneratesDraftsOnceButNeverSavesEvents()throws Exception {
        show(null);String id=inbox.offer(scope,"明天上午9点开会一小时");importText(id,true);
        long deadline=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<deadline){JSONArray drafts=state().optJSONArray("drafts");if(drafts!=null&&drafts.length()==1)break;SystemClock.sleep(30);}
        JSONArray drafts=state().getJSONArray("drafts");assertEquals(1,drafts.length());assertEquals("开会",drafts.getJSONObject(0).getString("title"));assertEquals("明天上午9点开会一小时",input());assertNull(inbox.get(id));assertTrue("A generated proposal is not a saved calendar entry",store.list(scope).isEmpty());
        String once=drafts.getJSONObject(0).getString("id");importText(id,true);SystemClock.sleep(100);assertEquals(once,state().getJSONArray("drafts").getJSONObject(0).getString("id"));
    }

    @Test public void ordinaryInboxReviewOnlyAddsEditableTextAndSurvivesReopening()throws Exception {
        show(null);String id=inbox.offer(scope,"可以先修改这段识别文字");scenario.onActivity(activity->planner.importRecognizedText(id));
        assertEquals("可以先修改这段识别文字",input());assertEquals(0,state().getJSONArray("drafts").length());assertTrue(store.list(scope).isEmpty());
        scenario.onActivity(activity->{planner.close();planner=new NativePlanningDialog(isolated,store,engine,scope,LocalDate.of(2026,10,28),ignored->{});planner.show();});
        assertEquals("可以先修改这段识别文字",input());assertEquals(0,state().getJSONArray("drafts").length());
    }

    @Test public void overLimitAppendPreservesEveryCharacterAndThePendingEntry()throws Exception {
        String existing=String.join("",Collections.nCopies(1990,"原")),recognized="明天上午9点开会一小时";JSONObject original=original(existing);show(original);String id=inbox.offer(scope,recognized);
        importText(id,true);choice(AlertDialog.BUTTON_POSITIVE);assertEquals(existing,input());assertEquals(existing,state().getString("text"));assertEquals(recognized,inbox.get(id).text);assertEquals(original.getJSONArray("drafts").toString(),state().getJSONArray("drafts").toString());assertTrue(store.list(scope).isEmpty());
        scenario.onActivity(activity->{
            AlertDialog dialog=(AlertDialog)field("dialog");((EditText)dialog.findViewById(R.id.planner_input)).setText("");
            ((android.view.View)field("rows")).findViewWithTag("draft_remove").performClick();
            ((android.widget.Button)field("pendingOcr")).performClick();
            assertFalse("Ordinary inbox review cannot inherit an earlier organize-now action",(Boolean)field("busy"));
        });
        assertEquals(recognized,input());assertEquals(0,state().getJSONArray("drafts").length());assertTrue(store.list(scope).isEmpty());
    }

    @Test public void aDifferentLocalDatasetCannotReplaceThisDraft()throws Exception {
        JSONObject original=original("当前资料输入");show(original);String id=inbox.offer("local:"+UUID.randomUUID(),"其他资料的识别文字");importText(id,true);
        assertEquals("当前资料输入",input());assertEquals(original.getJSONArray("drafts").toString(),state().getJSONArray("drafts").toString());assertNotNull(inbox.get(id));assertTrue(store.list(scope).isEmpty());
    }

    @Test public void inboxPersistsOnlyConfirmedTextAndRejectsEmptyOversizedOrFullQueues()throws Exception {
        OcrTextInbox queue=new OcrTextInbox(app,namespace+"queue");String full=String.join("",Collections.nCopies(2000,"字")),id=queue.offer(scope,full);assertEquals(full,new OcrTextInbox(app,namespace+"queue").get(id).text);
        for(String invalid:new String[]{" ",full+"字"})try{queue.offer(scope,invalid);fail("Text must be rejected, never truncated");}catch(IllegalArgumentException expected){}
        for(int i=1;i<OcrTextInbox.MAX_PENDING;i++)queue.offer(scope,"识别文字"+i);try{queue.offer(scope,"不能覆盖最早内容");fail("Full queue must retain all older entries");}catch(IllegalStateException expected){}
        assertEquals(id,queue.peek(scope).id);assertEquals(full,queue.peek(scope).text);queue.discard(id);assertNull(queue.get(id));assertNotNull(queue.offer(scope,"处理后可继续"));
    }
}
