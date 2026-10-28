package com.shunshoupai.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** The next system alarm is rebuilt from current calendar state, never from a stale Intent payload. */
final class ReminderScheduler {
    static final String ACTION="com.shunshoupai.app.CALENDAR_REMINDER",CHANNEL="calendar-reminders",TAG="schedule-reminder:";
    static final long ADVANCE=300_000,LATE_WINDOW=900_000;
    private final Context context; private final LocalScheduleStore store; private final SharedPreferences preferences;
    private final Consumer<String> onNotice; private final LongSupplier now; private final boolean systemDelivery;
    private long scheduledAt=-1; private boolean closed;
    ReminderScheduler(Context context,LocalScheduleStore store,Consumer<String> onNotice){this(context,store,onNotice,System::currentTimeMillis,true);}
    // Isolated calendar/receipt tests must not install system alarms for their temporary databases.
    ReminderScheduler(Context context,LocalScheduleStore store,Consumer<String> onNotice,LongSupplier now){this(context,store,onNotice,now,false);}
    private ReminderScheduler(Context context,LocalScheduleStore store,Consumer<String> onNotice,LongSupplier now,boolean systemDelivery){
        this.context=context;this.store=store;this.onNotice=onNotice;this.now=now;this.systemDelivery=systemDelivery;
        preferences=context.getSharedPreferences("foreground-reminders-"+store.deviceId(),Context.MODE_PRIVATE);
        if(systemDelivery)channels(context);
    }
    static void channels(Context context){
        NotificationChannel channel=new NotificationChannel(CHANNEL,"日程提醒",NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("日程开始前5分钟提醒，后台和锁屏时也可通知");channel.enableVibration(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    static boolean notificationsAllowed(Context context){
        NotificationManager manager=context.getSystemService(NotificationManager.class);NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE);
    }
    static boolean exactAllowed(Context context){return Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager.class).canScheduleExactAlarms();}
    boolean isEnabled(){return preferences.getBoolean("enabled",true);}
    synchronized void setEnabled(boolean value){preferences.edit().putBoolean("enabled",value).commit();scheduledAt=-1;refresh();}
    void onForeground(boolean ignored){refresh();}
    synchronized void onAlarm(){scheduledAt=-1;refresh();}
    synchronized void refresh(){
        if(closed)return;
        if(!isEnabled()){cancelAlarm();cancelObsolete(Collections.emptyMap());return;}
        long current=now.getAsLong();Set<String> shown=new LinkedHashSet<>(preferences.getStringSet("shown",Collections.emptySet()));
        shown.removeIf(value->{try{return Long.parseLong(value.substring(value.lastIndexOf('|')+1))<current-86_400_000;}catch(RuntimeException malformed){return true;}});
        long next=Long.MAX_VALUE;List<JSONObject> due=new ArrayList<>();Map<String,String> valid=new HashMap<>();
        try{
            for(JSONObject event:store.list(store.primaryScope())){
                if(event.optBoolean("deleted")||!"planned".equals(event.optString("status"))||(event.optJSONArray("replica_conflicts")!=null&&event.getJSONArray("replica_conflicts").length()>1))continue;
                long start=start(event);String key=key(event,start);valid.put(TAG+event.getString("id"),key);
                if(shown.contains(key)||start<current-LATE_WINDOW)continue;
                if(start-ADVANCE<=current)due.add(event);else next=Math.min(next,start-ADVANCE);
            }
            cancelObsolete(valid);
            // Keep future alarms so granting notifications in system settings also works
            // without reopening the calendar. Never mark suppressed reminders as delivered.
            if(systemDelivery&&!notificationsAllowed(context)){schedule(next);return;}
            for(JSONObject event:due){long start=start(event);if(!systemDelivery||notify(event,start,current)){
                shown.add(key(event,start));onNotice.accept((start>current?"即将开始：":"已经开始：")+event.getString("title"));
            }}
            // Stable notification tags make a process-death retry replace the same notification.
            preferences.edit().putStringSet("shown",shown).commit();schedule(next);
        }catch(Exception unavailable){schedule(current+60_000);}
    }
    private static long start(JSONObject event)throws Exception{return LocalDateTime.parse(event.getString("start_local"),LocalScheduleStore.LOCAL).atZone(LocalScheduleStore.ZONE).toInstant().toEpochMilli();}
    private static String key(JSONObject event,long start)throws Exception{return event.getString("id")+"|"+start;}
    private PendingIntent alarm(){return PendingIntent.getBroadcast(context,2404,new Intent(context,ReminderReceiver.class).setAction(ACTION),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private void schedule(long next){
        if(!systemDelivery||next==scheduledAt)return;if(next==Long.MAX_VALUE){cancelAlarm();return;}
        AlarmManager alarms=context.getSystemService(AlarmManager.class);
        try{if(exactAllowed(context))alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,alarm());else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,alarm());scheduledAt=next;}
        catch(SecurityException changedPermission){alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,alarm());scheduledAt=next;}
    }
    private void cancelAlarm(){if(systemDelivery&&scheduledAt!=0)context.getSystemService(AlarmManager.class).cancel(alarm());scheduledAt=0;}
    private void cancelObsolete(Map<String,String> valid){
        if(!systemDelivery)return;NotificationManager manager=context.getSystemService(NotificationManager.class);
        for(StatusBarNotification notice:manager.getActiveNotifications())if(notice.getTag()!=null&&notice.getTag().startsWith(TAG)
            &&!Objects.equals(valid.get(notice.getTag()),notice.getNotification().extras.getString("calendar_key")))manager.cancel(notice.getTag(),notice.getId());
    }
    private boolean notify(JSONObject event,long start,long current)throws Exception{
        String id=event.getString("id");Intent intent=new Intent(context,MainActivity.class).setAction(MainActivity.ACTION_REMINDER)
            .setData(android.net.Uri.parse("shunshoupai://reminder/"+id)).putExtra("event_id",id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification publicNotice=new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_reminder).setContentTitle("顺手排日程提醒").setContentText("解锁后查看安排").build();
        android.os.Bundle extras=new android.os.Bundle();extras.putString("calendar_key",key(event,start));
        Notification notification=new Notification.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_reminder).setContentTitle(event.getString("title"))
            .setContentText((start>current?"即将开始 · ":"已经开始 · ")+event.getString("start_local").substring(11,16)).setContentIntent(open)
            .setWhen(start).setShowWhen(true).setAutoCancel(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_EVENT)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicNotice).addExtras(extras).build();
        try{context.getSystemService(NotificationManager.class).notify(TAG+id,0,notification);return true;}catch(SecurityException denied){return false;}
    }
    // System alarms deliberately outlive the activity and this Java object.
    synchronized void close(){closed=true;}
}
