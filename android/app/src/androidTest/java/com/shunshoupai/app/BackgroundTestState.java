package com.shunshoupai.app;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

/** Stop the longer-lived production runtime before an isolated test replaces its SQLite file. */
final class BackgroundTestState {
    static void reset() {
        Context context=ApplicationProvider.getApplicationContext();
        java.util.concurrent.atomic.AtomicReference<CalendarRuntime> old=new java.util.concurrent.atomic.AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            try{java.lang.reflect.Field field=CalendarRuntime.class.getDeclaredField("instance");field.setAccessible(true);old.set((CalendarRuntime)field.get(null));}
            catch(Exception error){throw new AssertionError(error);}
            context.getSharedPreferences("background-operation",Context.MODE_PRIVATE).edit().putBoolean("sync",false).remove("setup_step").commit();
            SyncMaintenance.schedule(context,false);BackgroundSyncService.stop(context);
            context.getSystemService(android.app.NotificationManager.class).cancelAll();
        });
        long until=SystemClock.elapsedRealtime()+5000;while(BackgroundSyncService.isRunning()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(40);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(CalendarRuntime::disposeUnused);
        if(old.get()!=null)try{
            java.lang.reflect.Field field=ScheduleSync.class.getDeclaredField("worker");field.setAccessible(true);
            java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field.get(old.get().sync);
            if(!worker.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Previous test still owns the calendar runtime");
        }catch(Exception error){throw new AssertionError(error);}
    }
}
