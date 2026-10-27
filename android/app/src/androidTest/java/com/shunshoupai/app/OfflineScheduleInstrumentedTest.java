package com.shunshoupai.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Bitmap;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDateTime;
import java.io.File;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
public class OfflineScheduleInstrumentedTest {
    private LocalScheduleStore store;
    private Context context;
    @Before public void open(){context=ApplicationProvider.getApplicationContext();context.getSharedPreferences("connection",Context.MODE_PRIVATE).edit().clear().commit();context.getSharedPreferences("offline-editor",Context.MODE_PRIVATE).edit().clear().commit();context.deleteDatabase("schedule-offline.sqlite");store=new LocalScheduleStore(context);}
    @After public void close(){store.close();context.deleteDatabase("schedule-offline.sqlite");}
    private JSONObject payload(String title)throws Exception{return LocalScheduleStore.payload(title,LocalDateTime.of(2026,10,27,10,0),LocalDateTime.of(2026,10,27,11,0),"planned",false);}
    private JSONObject remote(String id,String title,int version,boolean deleted)throws Exception{return new JSONObject().put("id",id).put("title",title).put("start_at","2026-10-27T02:00:00.000Z").put("end_at","2026-10-27T03:00:00.000Z").put("status","planned").put("version",version).put("deleted_at",deleted?"2026-10-27T04:00:00.000Z":JSONObject.NULL);}
    private JSONObject response(JSONObject mutation,JSONObject event,String status)throws Exception{return new JSONObject().put("results",new JSONArray().put(new JSONObject().put("mutation_id",mutation.getString("mutation_id")).put("status",status).put("event",event))).put("events",new JSONArray().put(event));}

    @Test public void firstUsePersistsWithoutServerAndOnlyFirstAccountReceivesGuestEvents()throws Exception{
        String id=store.save("guest",null,payload("独立使用"));store.close();store=new LocalScheduleStore(context);
        assertEquals("独立使用",store.list("guest").get(0).getString("title"));
        String a=store.bind("http://computer/",new JSONObject().put("id","a").put("email","a@example.test"));
        assertEquals(id,store.list(a).get(0).getString("id"));assertTrue(store.list("guest").isEmpty());
        String b=store.bind("http://computer/",new JSONObject().put("id","b").put("email","b@example.test"));
        assertTrue(store.list(b).isEmpty());assertEquals(1,store.list(a).size());
        assertEquals(b,store.cachedScope("http://computer/"));
    }

    @Test public void uncertainResultRetriesIdenticalMutationAndEditsDuringUploadAreKept()throws Exception{
        String id=store.save("a",null,payload("原始内容"));JSONObject mutation=store.mutations("a").getJSONObject(0);
        store.save("a",id,payload("上传时继续编辑"));store.close();store=new LocalScheduleStore(context);
        assertEquals(mutation.toString(),store.mutations("a").getJSONObject(0).toString());
        store.apply("a",response(mutation,remote(id,"原始内容",1,false),"applied"));
        assertEquals("上传时继续编辑",store.list("a").get(0).getString("title"));
        JSONObject next=store.mutations("a").getJSONObject(0);assertEquals(1,next.getInt("base_version"));
        assertNotEquals(mutation.getString("mutation_id"),next.getString("mutation_id"));
        store.apply("a",response(next,remote(id,"上传时继续编辑",2,false),"applied"));assertEquals(0,store.mutations("a").length());
    }

    @Test public void conflictRetainsPhoneDraftAndKeepBothCreatesOneNewId()throws Exception{
        String id=store.save("a",null,payload("手机版本"));JSONObject mutation=store.mutations("a").getJSONObject(0);
        store.apply("a",response(mutation,remote(id,"电脑版本",3,false),"conflict"));
        JSONObject conflicted=store.list("a").get(0);assertEquals("手机版本",conflicted.getString("title"));
        assertEquals("电脑版本",conflicted.getJSONObject("conflict").getString("title"));assertEquals(0,store.mutations("a").length());
        store.resolve("a",conflicted,true);assertEquals(2,store.list("a").size());
        JSONObject copy=store.mutations("a").getJSONObject(0);assertNotEquals(id,copy.getString("id"));assertEquals(0,copy.getInt("base_version"));
    }

    @Test public void remoteTombstoneRemovesVisibleEventButCannotEraseUnsyncedEdit()throws Exception{
        String id=store.save("a",null,payload("本地新建"));JSONObject mutation=store.mutations("a").getJSONObject(0);
        store.apply("a",response(mutation,remote(id,"本地新建",1,false),"applied"));
        JSONObject tombstone=new JSONObject().put("results",new JSONArray()).put("events",new JSONArray().put(remote(id,"已删",2,true)));
        store.apply("a",tombstone);assertTrue(store.list("a").get(0).getBoolean("deleted"));
        String other=store.save("a",null,payload("待同步"));
        store.apply("a",new JSONObject().put("results",new JSONArray()).put("events",new JSONArray().put(remote(other,"远端",2,true))));
        assertTrue(store.list("a").stream().anyMatch(event->event.optString("id").equals(other)&&!event.optBoolean("deleted")));
    }

    @Test public void confirmingAlreadyDeletedRemoteDoesNotQueueAnotherDelete()throws Exception{
        String id=store.save("a",null,payload("待删"));JSONObject created=store.mutations("a").getJSONObject(0);
        store.apply("a",response(created,remote(id,"待删",1,false),"applied"));
        JSONObject deleted=payload("待删").put("deleted",true);store.save("a",id,deleted);
        JSONObject mutation=store.mutations("a").getJSONObject(0);
        store.apply("a",response(mutation,remote(id,"待删",2,true),"conflict"));
        store.resolve("a",store.list("a").get(0),true);
        assertEquals(0,store.mutations("a").length());assertTrue(store.list("a").get(0).getBoolean("deleted"));
    }

    @Test public void editorKeepsItsOriginalVersionWhenComputerChangesWhileDialogIsOpen()throws Exception{
        String id=store.save("a",null,payload("打开编辑器"));JSONObject created=store.mutations("a").getJSONObject(0);
        store.apply("a",response(created,remote(id,"打开编辑器",1,false),"applied"));
        int editorVersion=store.list("a").get(0).getInt("version");
        store.apply("a",new JSONObject().put("results",new JSONArray()).put("events",new JSONArray().put(remote(id,"电脑的新修改",2,false))));
        store.save("a",id,payload("尚未关闭的手机表单"),editorVersion);
        assertEquals(2,store.list("a").get(0).getInt("version"));
        JSONObject stale=store.mutations("a").getJSONObject(0);assertEquals(1,stale.getInt("base_version"));
        store.close();store=new LocalScheduleStore(context);
        assertEquals(stale.toString(),store.mutations("a").getJSONObject(0).toString());
        store.apply("a",response(stale,remote(id,"电脑的新修改",2,false),"conflict"));
        assertEquals("尚未关闭的手机表单",store.list("a").get(0).getString("title"));
        assertEquals("电脑的新修改",store.list("a").get(0).getJSONObject("conflict").getString("title"));
    }

    @Test public void toggleAndDeleteCannotEraseAnEarlierEditorBase()throws Exception{
        String id=store.save("a",null,payload("打开编辑器"));JSONObject created=store.mutations("a").getJSONObject(0);
        store.apply("a",response(created,remote(id,"打开编辑器",1,false),"applied"));
        store.apply("a",new JSONObject().put("results",new JSONArray()).put("events",new JSONArray().put(remote(id,"电脑更新",2,false))));
        store.save("a",id,payload("旧手机表单"),1);
        store.save("a",id,payload("旧手机表单").put("status","completed"));
        store.save("a",id,payload("旧手机表单").put("deleted",true));
        assertEquals(1,store.mutations("a").getJSONObject(0).getInt("base_version"));
    }

    @Test public void reopeningAnUnsyncedEditorKeepsOriginalBaseAcrossRestart()throws Exception{
        String id=store.save("a",null,payload("打开编辑器"));JSONObject created=store.mutations("a").getJSONObject(0);
        store.apply("a",response(created,remote(id,"打开编辑器",1,false),"applied"));
        store.apply("a",new JSONObject().put("results",new JSONArray()).put("events",new JSONArray().put(remote(id,"电脑更新",2,false))));
        store.save("a",id,payload("旧手机表单"),1);store.close();store=new LocalScheduleStore(context);
        JSONObject reopened=store.list("a").get(0);assertEquals(2,reopened.getInt("version"));assertEquals(1,reopened.getInt("base_version"));
        store.save("a",id,payload("再次编辑"),reopened.getInt("base_version"));
        // Also defend callers holding a newer server version rather than the editor baseline.
        store.save("a",id,payload("仍保留原基线"),2);
        assertEquals(1,store.mutations("a").getJSONObject(0).getInt("base_version"));
    }

    @Test public void nativeEditorRestoresDraftAfterRecreationAndSavesWithoutComputer()throws Exception{
        String id=store.save("guest",null,payload("手机保留的安排"));JSONObject mutation=store.mutations("guest").getJSONObject(0);
        store.apply("guest",response(mutation,remote(id,"电脑修改的安排",3,false),"conflict"));
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity->{assertEquals(View.VISIBLE,activity.findViewById(R.id.offline_screen).getVisibility());activity.findViewById(R.id.offline_add).performClick();});
            AccessibilityNodeInfo editor=awaitEditable();Bundle arguments=new Bundle();arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"手机离线安排");assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments));
            SystemClock.sleep(200);scenario.recreate();
            assertEquals("手机离线安排",awaitEditable().getText().toString());capture("android-offline-editor.png");
            clickText("保存");SystemClock.sleep(300);
            assertTrue(store.list("guest").stream().anyMatch(event->"手机离线安排".equals(event.optString("title"))));capture("android-offline-home.png");
            clickText("处理合并");SystemClock.sleep(200);capture("android-offline-conflict.png");
            assertFalse(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("电脑修改的安排").isEmpty());
        }
    }

    private AccessibilityNodeInfo editable(AccessibilityNodeInfo node){if(node==null)return null;if(node.isEditable()&&node.isFocused())return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=editable(node.getChild(i));if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo awaitEditable(){long deadline=SystemClock.elapsedRealtime()+5000;while(SystemClock.elapsedRealtime()<deadline){AccessibilityNodeInfo found=editable(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow());if(found!=null)return found;SystemClock.sleep(100);}throw new AssertionError("Native title editor should remain focused after recreation");}
    private void clickText(String text){long deadline=SystemClock.elapsedRealtime()+5000;while(SystemClock.elapsedRealtime()<deadline){AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(text.contentEquals(node.getText()==null?"":node.getText())&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;SystemClock.sleep(100);}throw new AssertionError("Missing native action: "+text);}
    private void capture(String name)throws Exception{InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(500);Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);try(FileOutputStream output=new FileOutputStream(new File(context.getExternalFilesDir(null),name))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}finally{image.recycle();}}
}
