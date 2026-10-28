package com.shunshoupai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System-only recovery after boot/unlock, package upgrade and clock/permission changes. */
public final class CalendarRestoreReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !java.util.Arrays.asList(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED).contains(intent.getAction())) return;
        CalendarRuntime runtime = CalendarRuntime.get(context);
        runtime.reminders.onAlarm();
        runtime.maintainBackground();
        if (BackgroundSyncService.enabled(context) && runtime.hasPeers()) BackgroundSyncService.ensureStarted(context);
    }
}
