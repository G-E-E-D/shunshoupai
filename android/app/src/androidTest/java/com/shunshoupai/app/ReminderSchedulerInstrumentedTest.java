package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Foreground-only behavior is checked against a fixed phone clock, with no server. */
@RunWith(AndroidJUnit4.class)
public class ReminderSchedulerInstrumentedTest {
    private Context context;private LocalScheduleStore store;private ReminderScheduler reminder;private String database;
    private final List<String> notices=new CopyOnWriteArrayList<>();
    private final AtomicLong now=new AtomicLong(LocalDateTime.of(2036,2,1,9,55).atZone(LocalScheduleStore.ZONE).toInstant().toEpochMilli());
    @Before public void open(){
        String prefix="reminder-test-"+UUID.randomUUID()+"-";
        context=new ContextWrapper(ApplicationProvider.getApplicationContext()){
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefix+name,mode);}
            @Override public Context getApplicationContext(){return this;}
        };
        database=prefix+"events.sqlite";store=new LocalScheduleStore(context,database);store.initialize("");
        reminder=new ReminderScheduler(context,store,notices::add,now::get);
    }
    @After public void close(){if(reminder!=null)main(reminder::close);store.close();context.deleteDatabase(database);}
    private void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private String save(String title,int minute,String status,boolean deleted)throws Exception {
        LocalDateTime start=LocalDateTime.of(2036,2,1,10,minute);
        return store.save(store.primaryScope(),null,LocalScheduleStore.payload(title,start,start.plusMinutes(20),status,deleted));
    }
    @Test public void optInForegroundWindowIgnoresCompletedDeletedAndConflictedEvents()throws Exception {
        save("该提醒的本机安排",0,"planned",false);save("已完成不提醒",0,"completed",false);save("删除不提醒",0,"planned",true);save("六分钟之外",6,"planned",false);
        String conflict=save("冲突初稿",0,"planned",false);JSONObject record=null;
        JSONArray records=store.replica().page(null).getJSONArray("records");for(int i=0;i<records.length();i++)if(conflict.equals(records.getJSONObject(i).getString("id")))record=records.getJSONObject(i);
        assertNotNull(record);JSONArray heads=record.getJSONArray("heads");JSONObject payload=heads.getJSONObject(0).getJSONObject("payload");
        store.save(store.primaryScope(),conflict,new JSONObject(payload.toString()).put("title","本机冲突修改"));
        JSONObject alternative=AncestryReplica.createRevision(heads,UUID.randomUUID().toString(),new JSONObject(payload.toString()).put("title","另端冲突修改"),"测试手机",now.get());
        store.replica().merge(new JSONArray().put(new JSONObject().put("id",conflict).put("heads",new JSONArray().put(alternative))));
        assertFalse(reminder.isEnabled());main(()->{reminder.onForeground(true);reminder.refresh();});assertTrue(notices.isEmpty());
        main(()->{reminder.setEnabled(true);reminder.refresh();});assertEquals(1,notices.size());assertTrue(notices.get(0).contains("该提醒的本机安排"));
        main(reminder::refresh);assertEquals("Repeated refresh cannot repeat the same reminder",1,notices.size());
        main(()->reminder.onForeground(false));now.addAndGet(6*60*1000);main(reminder::refresh);assertEquals(1,notices.size());
        main(()->{reminder.onForeground(true);reminder.refresh();});assertEquals(2,notices.size());assertTrue(notices.get(1).contains("六分钟之外"));
        assertTrue(store.peers().isEmpty());
    }
    @Test public void enabledPreferenceSurvivesRestartAndCompletedEventDoesNotReturnAsReminder()throws Exception {
        String id=save("持久开关",0,"planned",false);main(()->reminder.setEnabled(true));main(reminder::close);
        reminder=new ReminderScheduler(context,store,notices::add,now::get);assertTrue(reminder.isEnabled());
        JSONObject row=store.list(store.primaryScope()).stream().filter(value->id.equals(value.optString("id"))).findFirst().get();
        store.save(store.primaryScope(),id,LocalScheduleStore.desired(row).put("status","completed"),row.getInt("base_version"));
        main(()->{reminder.onForeground(true);reminder.refresh();});assertTrue(notices.isEmpty());
        main(()->reminder.setEnabled(false));main(reminder::close);reminder=new ReminderScheduler(context,store,notices::add,now::get);assertFalse(reminder.isEnabled());
    }
}
