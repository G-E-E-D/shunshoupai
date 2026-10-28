package com.shunshoupai.app;

import static org.junit.Assert.*;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@RunWith(AndroidJUnit4.class)
public class BackgroundOperationInstrumentedTest {
    private Context context;private CalendarRuntime runtime;private ActivityScenario<MainActivity> scenario;
    private LocalScheduleStore remote;private DeviceHostRuntime remoteHost;private ScheduleSync remoteSync;
    @Before public void open()throws Exception{
        context=ApplicationProvider.getApplicationContext();BackgroundTestState.reset();context.deleteDatabase("schedule-offline.sqlite");
        context.getSharedPreferences("background-operation",Context.MODE_PRIVATE).edit().clear().commit();
        if(android.os.Build.VERSION.SDK_INT>=33)InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        scenario=ActivityScenario.launch(MainActivity.class);main(()->runtime=CalendarRuntime.get(context));
    }
    @After public void close()throws Exception{
        shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");
        if(scenario!=null)scenario.close();if(remoteHost!=null)remoteHost.stop();if(remoteSync!=null)remoteSync.close();
        BackgroundTestState.reset();
    }
    private void main(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private void await(String message,BooleanSupplier condition,long timeout){long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(condition.getAsBoolean())return;SystemClock.sleep(100);}assertTrue(message,condition.getAsBoolean());}
    private void shell(String command)throws Exception{try(android.os.ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);java.io.FileInputStream input=new java.io.FileInputStream(descriptor.getFileDescriptor())){while(input.read()!=-1){}}}
    private boolean contains(LocalScheduleStore store,String title){try{return store.list(store.primaryScope()).stream().anyMatch(row->title.equals(row.optString("title")));}catch(Exception error){throw new AssertionError(error);}}
    private JSONObject payload(String title)throws Exception{return LocalScheduleStore.payload(title,LocalDateTime.now(LocalScheduleStore.ZONE).plusDays(1),LocalDateTime.now(LocalScheduleStore.ZONE).plusDays(1).plusHours(1),"planned",false);}
    private void pair()throws Exception{
        remote=new LocalScheduleStore(context,"background-peer-"+UUID.randomUUID()+".sqlite");remote.initialize("");remote.rememberListenPort(0);
        Handler handler=new Handler(Looper.getMainLooper());remoteHost=new DeviceHostRuntime(context,remote,handler,()->{});
        String origin="http://127.0.0.1:"+remoteHost.start()+"/";
        remoteSync=new ScheduleSync(context,remote,handler,remoteHost,(o,s,m,p)->{});
        ScannedConnection code=ScannedConnection.parse(origin+"#pair="+remoteHost.issueTicket().getString("token"));
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();
        main(()->{runtime.sync.setReturnOrigin(()->"http://127.0.0.1:"+runtime.host.port()+"/");runtime.sync.pair(code,(o,s,e)->{error.set(e);runtime.localChanged();done.countDown();});});
        assertTrue(done.await(10,TimeUnit.SECONDS));assertNull(error.get());await("Foreground peer service started",BackgroundSyncService::isRunning,5000);
    }
    @Test public void closedActivityAndLockedScreenStillAutomaticallyExchangeBothCalendars()throws Exception{
        pair();int port=runtime.host.port();scenario.close();scenario=null;
        assertTrue(runtime.host.isRunning());assertEquals(port,runtime.host.port());
        shell("input keyevent KEYCODE_SLEEP");assertFalse(context.getSystemService(android.os.PowerManager.class).isInteractive());
        runtime.store.save(runtime.store.primaryScope(),null,payload("锁屏手机待同步"));remote.save(remote.primaryScope(),null,payload("对方新日程"));
        // No manual sync request: the service's regular pass must carry both changes.
        await("Automatic background upload",()->contains(remote,"锁屏手机待同步"),45000);
        await("Automatic background download",()->contains(runtime.store,"对方新日程"),10000);
        assertFalse(context.getSystemService(android.os.PowerManager.class).isInteractive());
        remote.save(remote.primaryScope(),null,payload("后台接收推送"));main(remoteSync::sync);
        await("Listener accepts authenticated inbound replica exchange after Activity destruction",()->contains(runtime.store,"后台接收推送"),10000);
    }
    @Test public void pauseCancelsPersistentJobAndListenerWithoutDeletingSchedules()throws Exception{
        pair();runtime.store.save(runtime.store.primaryScope(),null,payload("暂停后保留"));scenario.moveToState(Lifecycle.State.CREATED);
        main(()->BackgroundSyncService.setEnabled(context,false));await("Service stopped",()->!BackgroundSyncService.isRunning(),5000);
        assertFalse(runtime.host.isRunning());assertNull(context.getSystemService(android.app.job.JobScheduler.class).getPendingJob(SyncMaintenance.JOB));
        assertTrue(contains(runtime.store,"暂停后保留"));assertTrue(runtime.store.peer(remote.deviceId()).getBoolean("enabled"));
        scenario.moveToState(Lifecycle.State.RESUMED);main(()->BackgroundSyncService.setEnabled(context,true));await("Service resumed",BackgroundSyncService::isRunning,5000);
    }
    @Test public void systemAlarmDeliversWithoutActivityAndCompletedOrRetimedEventsDoNotLeaveStaleNotifications()throws Exception{
        LocalDateTime start=LocalDateTime.now(LocalScheduleStore.ZONE).plusMinutes(5).plusSeconds(7);
        String id=runtime.store.save(runtime.store.primaryScope(),null,LocalScheduleStore.payload("真实系统闹钟提醒",start,start.plusHours(1),"planned",false));
        main(()->{runtime.reminders.setEnabled(true);runtime.reminders.refresh();});scenario.moveToState(Lifecycle.State.CREATED);
        await("AlarmManager delivered a notification in the background",()->java.util.Arrays.stream(context.getSystemService(NotificationManager.class).getActiveNotifications()).anyMatch(value->(ReminderScheduler.TAG+id).equals(value.getTag())),25000);
        JSONObject row=runtime.store.list(runtime.store.primaryScope()).get(0);
        runtime.store.save(runtime.store.primaryScope(),id,LocalScheduleStore.payload("改期后不沿用旧提醒",start.plusHours(2),start.plusHours(3),"planned",false),row.getInt("base_version"));
        main(runtime.reminders::refresh);
        assertFalse("Retiming removes the old notification",java.util.Arrays.stream(context.getSystemService(NotificationManager.class).getActiveNotifications()).anyMatch(value->(ReminderScheduler.TAG+id).equals(value.getTag())));
        row=runtime.store.list(runtime.store.primaryScope()).get(0);runtime.store.save(runtime.store.primaryScope(),id,LocalScheduleStore.desired(row).put("status","completed"),row.getInt("base_version"));
        main(runtime.reminders::refresh);
        assertFalse(java.util.Arrays.stream(context.getSystemService(NotificationManager.class).getActiveNotifications()).anyMatch(value->(ReminderScheduler.TAG+id).equals(value.getTag())));
        main(runtime.reminders::onAlarm);assertEquals(0,context.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }
    @Test public void disabledBackgroundChoiceSurvivesRestoreBroadcast()throws Exception{
        pair();main(()->BackgroundSyncService.setEnabled(context,false));await("Service stops",()->!BackgroundSyncService.isRunning(),5000);
        scenario.moveToState(Lifecycle.State.CREATED);
        main(()->new CalendarRestoreReceiver().onReceive(context,new android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED)));
        assertFalse(BackgroundSyncService.enabled(context));assertFalse(BackgroundSyncService.isRunning());assertFalse(runtime.host.isRunning());
    }
    @Test public void persistedJobCanMergeWhenContinuousServiceIsUnavailable()throws Exception{
        pair();scenario.moveToState(Lifecycle.State.CREATED);main(()->BackgroundSyncService.stop(context));
        await("Continuous service stopped",()->!BackgroundSyncService.isRunning(),5000);
        assertNotNull(context.getSystemService(android.app.job.JobScheduler.class).getPendingJob(SyncMaintenance.JOB));
        remote.save(remote.primaryScope(),null,payload("系统任务补同步"));
        shell("cmd jobscheduler run -f com.shunshoupai.app "+SyncMaintenance.JOB);
        await("Real JobService performs deferred merge",()->contains(runtime.store,"系统任务补同步"),15000);
        assertFalse("A job must not secretly recreate a foreground service",BackgroundSyncService.isRunning());
    }
    @Test public void nativeSettingsShowActualPermissionsAndPauseTheBackgroundService()throws Exception{
        pair();scenario.onActivity(activity->activity.findViewById(R.id.offline_reminders).performClick());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();SystemClock.sleep(500);
        android.graphics.Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"android-background-settings.png"))){assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}finally{image.recycle();}
        shell("mkdir -p /data/local/tmp/shunshoupai-background");shell("cp /sdcard/Android/data/com.shunshoupai.app/files/android-background-settings.png /data/local/tmp/shunshoupai-background/android-background-settings.png");
        AtomicReference<android.widget.Switch> toggle=new AtomicReference<>();
        main(()->{for(android.view.View root:android.view.inspector.WindowInspector.getGlobalWindowViews())findSyncSwitch(root,toggle);assertNotNull(toggle.get());assertTrue(toggle.get().isChecked());toggle.get().performClick();});
        assertFalse(BackgroundSyncService.enabled(context));await("Native pause switch stops service",()->!BackgroundSyncService.isRunning(),5000);
        assertTrue(runtime.store.peer(remote.deviceId()).getBoolean("enabled"));
    }
    private void findSyncSwitch(android.view.View view,AtomicReference<android.widget.Switch> result){
        if(view instanceof android.widget.Switch&&"后台自动同步日程".contentEquals(((android.widget.Switch)view).getText()))result.set((android.widget.Switch)view);
        if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)findSyncSwitch(group.getChildAt(i),result);}
    }
}
