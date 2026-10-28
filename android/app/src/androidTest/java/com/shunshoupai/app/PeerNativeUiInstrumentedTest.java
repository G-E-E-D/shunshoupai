package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class PeerNativeUiInstrumentedTest {
    private Context context;
    @Before public void clear(){context=ApplicationProvider.getApplicationContext();context.deleteDatabase("schedule-offline.sqlite");context.getSharedPreferences("connection",Context.MODE_PRIVATE).edit().clear().commit();context.getSharedPreferences("offline-editor",Context.MODE_PRIVATE).edit().clear().commit();}
    private Object field(MainActivity activity,String name){try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}catch(Exception error){throw new AssertionError(error);}}
    @Test public void qrHostRemainsActiveInDialogAndRestartsOnSamePortAfterForegroundResume()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<DeviceHostRuntime> host=new AtomicReference<>();scenario.onActivity(activity->{host.set((DeviceHostRuntime)field(activity,"deviceHost"));assertTrue(host.get().isRunning());activity.findViewById(R.id.offline_connect).performClick();});
            int port=host.get().port();capture("android-devices.png");
            scenario.onActivity(activity->activity.findViewById(R.id.show_pairing_code).performClick());
            waitText("让另一台设备扫描");assertTrue(host.get().isRunning());waitText("模型密钥和设置不传播");click("返回");
            scenario.moveToState(Lifecycle.State.CREATED);assertFalse(host.get().isRunning());scenario.moveToState(Lifecycle.State.RESUMED);assertTrue(host.get().isRunning());assertEquals(port,host.get().port());
        }
    }
    @Test public void nativeConflictChoiceShowsEveryPayloadAndExplicitlyResolvesObservedHeads()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            AtomicReference<LocalScheduleStore> local=new AtomicReference<>();AtomicReference<String> eventId=new AtomicReference<>();
            scenario.onActivity(activity->{try{
                LocalScheduleStore store=(LocalScheduleStore)field(activity,"scheduleStore");local.set(store);LocalDateTime start=LocalDateTime.now(LocalScheduleStore.ZONE).withHour(9).withMinute(0).withSecond(0).withNano(0);
                JSONObject base=LocalScheduleStore.payload("周末散步",start,start.plusHours(1),"planned",false);String id=store.save(store.primaryScope(),null,base);eventId.set(id);
                JSONArray observed=store.replica().page(null).getJSONArray("records").getJSONObject(0).getJSONArray("heads");
                store.save(store.primaryScope(),id,new JSONObject(base.toString()).put("title","手机A：公园散步"));
                JSONObject alternate=AncestryReplica.createRevision(observed,UUID.randomUUID().toString(),new JSONObject(base.toString()).put("title","手机B：河边散步"),"另一部手机",System.currentTimeMillis());
                store.replica().merge(new JSONArray().put(new JSONObject().put("id",id).put("heads",new JSONArray().put(alternate))));
                ((OfflineScheduleView)activity.findViewById(R.id.offline_screen)).refresh();
            }catch(Exception error){throw new AssertionError(error);}});
            capture("android-peer-home.png");click("处理合并");waitText("手机A：公园散步");waitText("手机B：河边散步");capture("android-peer-conflict.png");
            clickContaining("手机B：河边散步");click("确认合并");
            scenario.onActivity(activity->{try{JSONObject row=local.get().list(local.get().primaryScope()).get(0);assertEquals(eventId.get(),row.getString("id"));assertEquals("手机B：河边散步",row.getString("title"));assertFalse(row.has("replica_conflicts"));}catch(Exception error){throw new AssertionError(error);}});
        }
    }
    @Test public void multiplePairedComputersOfferAnExplicitToolDestination()throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(activity->{try{
                LocalScheduleStore store=(LocalScheduleStore)field(activity,"scheduleStore");
                for(int i=0;i<2;i++){
                    TestPeer peer=new TestPeer();String challenge=PeerIdentity.challenge();JSONObject proof=peer.proof(challenge,store.deviceId());proof.getJSONObject("device").put("name",i==0?"工作电脑 A":"个人电脑 B");
                    store.registerPeer("http://127.0.0.1:"+(41001+i)+"/",PeerIdentity.verify(proof,challenge,store.deviceId(),null,null,null),UUID.randomUUID().toString(),proof.getJSONObject("capabilities"));
                }
                activity.findViewById(R.id.offline_online).performClick();
            }catch(Exception error){throw new AssertionError(error);}});
            waitText("选择运行工具的电脑");
            scenario.onActivity(activity->{boolean clicked=false;for(android.view.View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(selectListItem(root,"个人电脑 B")){clicked=true;break;}assertTrue("Choose the visible computer row",clicked);});
            scenario.onActivity(activity->assertEquals("http://127.0.0.1:41002/",field(activity,"serverAddress")));
        }
    }
    private boolean selectListItem(android.view.View view,String label){
        if(view instanceof android.widget.ListView){android.widget.ListView list=(android.widget.ListView)view;for(int i=0;i<list.getCount();i++)if(label.equals(String.valueOf(list.getAdapter().getItem(i))))return list.performItemClick(list.getChildAt(i-list.getFirstVisiblePosition()),i,list.getAdapter().getItemId(i));}
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(selectListItem(group.getChildAt(i),label))return true;}return false;
    }
    private AccessibilityNodeInfo waitText(String text){long until=SystemClock.elapsedRealtime()+5000;while(SystemClock.elapsedRealtime()<until){AccessibilityNodeInfo root=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null&&node.getText().toString().contains(text))return node;SystemClock.sleep(100);}throw new AssertionError("Missing native text: "+text);}
    private void clickContaining(String text){AccessibilityNodeInfo node=waitText(text);while(node!=null){if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;node=node.getParent();}throw new AssertionError("Not clickable: "+text);}
    private void click(String text){clickContaining(text);}
    private void capture(String name)throws Exception{InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(400);Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);try(FileOutputStream output=new FileOutputStream(new File(context.getExternalFilesDir(null),name))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}finally{image.recycle();}}
}
