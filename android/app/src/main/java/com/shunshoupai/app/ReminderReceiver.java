package com.shunshoupai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** AlarmManager holds the delivery wake lock; all work is local and bounded. */
public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && ReminderScheduler.ACTION.equals(intent.getAction())) CalendarRuntime.get(context).reminders.onAlarm();
    }
}
