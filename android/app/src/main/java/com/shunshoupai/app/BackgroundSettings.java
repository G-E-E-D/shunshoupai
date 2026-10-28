package com.shunshoupai.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

/** User-initiated, resumable setup. Ordinary launches never repeat permission dialogs. */
final class BackgroundSettings {
    private final ComponentActivity activity;
    private final CalendarRuntime runtime;
    private final Runnable changed;
    private final SharedPreferences prefs;
    private final ActivityResultLauncher<String> notification;
    private final ActivityResultLauncher<Intent> setting;
    private AlertDialog dialog;
    private TextView summary;
    BackgroundSettings(ComponentActivity activity, CalendarRuntime runtime, Runnable changed) {
        this.activity=activity; this.runtime=runtime; this.changed=changed;
        prefs=activity.getSharedPreferences("background-operation",Context.MODE_PRIVATE);
        notification=activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->next());
        setting=activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->next());
    }
    static boolean unrestricted(Context context){return context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName());}
    String hint(){
        if(runtime.reminders.isEnabled()&&!ReminderScheduler.notificationsAllowed(activity))return "允许通知，后台和锁屏也能提醒";
        if(runtime.reminders.isEnabled()&&!ReminderScheduler.exactAllowed(activity))return "允许准时提醒";
        if(BackgroundSyncService.enabled(activity)&&runtime.hasPeers()&&!unrestricted(activity))return "允许锁屏持续同步 · 一次设置";
        if(BackgroundSyncService.enabled(activity)&&runtime.hasPeers()&&!BackgroundSyncService.isRunning())return "恢复后台自动同步";
        return "";
    }
    void show(){
        if(dialog!=null)dialog.dismiss();LinearLayout content=NativeUi.column(activity);int p=NativeUi.dp(activity,20);content.setPadding(p,0,p,p);
        summary=NativeUi.text(activity,"",14);content.addView(summary);
        Switch reminder=new Switch(activity);reminder.setText("日程开始前5分钟提醒");reminder.setChecked(runtime.reminders.isEnabled());reminder.setMinHeight(NativeUi.dp(activity,52));content.addView(reminder);
        reminder.setOnCheckedChangeListener((button,enabled)->{runtime.reminders.setEnabled(enabled);refresh();});
        Switch sync=new Switch(activity);sync.setText("后台自动同步日程");sync.setChecked(BackgroundSyncService.enabled(activity));sync.setMinHeight(NativeUi.dp(activity,52));content.addView(sync);
        sync.setOnCheckedChangeListener((button,enabled)->{BackgroundSyncService.setEnabled(activity,enabled);refresh();});
        Button setup=NativeUi.button(activity,"一次设置通知与锁屏运行",true,this::begin);content.addView(setup);
        LinearLayout.LayoutParams secondary=new LinearLayout.LayoutParams(-1,-2);secondary.topMargin=NativeUi.dp(activity,8);
        Button notifications=NativeUi.button(activity,"系统通知设置",false,()->launch(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName())));content.addView(notifications,secondary);
        content.addView(NativeUi.text(activity,"只与已配对设备交换日程，保留本机副本。后台同步显示安静的运行通知，可随时暂停。首次重启解锁后自动恢复；系统强行停止后需再打开一次。厂商如限制自启动，可在应用电池设置中允许。",13));
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(content);
        dialog=new AlertDialog.Builder(activity).setTitle("提醒与后台运行").setView(scroll).setPositiveButton("完成",null).create();dialog.show();refresh();
    }
    void refresh(){
        if(summary!=null)summary.setText("通知："+(ReminderScheduler.notificationsAllowed(activity)?"已允许":"未允许，无法显示提醒")
            +"\n提醒时间："+(ReminderScheduler.exactAllowed(activity)?"系统准时闹钟":"系统可能延后，可在一次设置中允许")
            +"\n后台同步："+(!BackgroundSyncService.enabled(activity)?"已暂停":!runtime.hasPeers()?"配对后自动开启":BackgroundSyncService.isRunning()?"正在运行":"等待系统恢复")
            +"\n锁屏连接："+(unrestricted(activity)?"已允许不受电池优化限制":"深度休眠可能延后同步，建议一次设置")+"\n");
        runtime.reminders.refresh();changed.run();
    }
    void begin(){prefs.edit().putInt("setup_step",0).apply();next();}
    private void next(){
        if(activity.isFinishing()||activity.isDestroyed())return;int step=prefs.getInt("setup_step",-1);
        if(step<0){refresh();return;}
        if(step==0){
            prefs.edit().putInt("setup_step",1).apply();
            if(runtime.reminders.isEnabled()&&!ReminderScheduler.notificationsAllowed(activity)){
                if(Build.VERSION.SDK_INT>=33&&activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!prefs.getBoolean("notifications_asked",false)){
                    prefs.edit().putBoolean("notifications_asked",true).apply();notification.launch(Manifest.permission.POST_NOTIFICATIONS);return;
                }
                launch(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,activity.getPackageName()));return;
            }step=1;
        }
        if(step==1){prefs.edit().putInt("setup_step",2).apply();
            if(runtime.reminders.isEnabled()&&Build.VERSION.SDK_INT>=31&&!ReminderScheduler.exactAllowed(activity)){launch(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+activity.getPackageName())));return;}step=2;
        }
        if(step==2){prefs.edit().putInt("setup_step",3).apply();
            // Peer-to-peer LAN sync has no cloud push service capable of waking the peers.
            if(BackgroundSyncService.enabled(activity)&&runtime.hasPeers()&&!unrestricted(activity)){launch(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+activity.getPackageName())));return;}
        }
        prefs.edit().remove("setup_step").apply();runtime.maintainBackground();refresh();
    }
    private void launch(Intent intent){try{setting.launch(intent);}catch(RuntimeException unavailable){prefs.edit().remove("setup_step").apply();Toast.makeText(activity,"请在系统应用设置中允许通知和后台运行。",Toast.LENGTH_LONG).show();refresh();}}
    void close(){if(dialog!=null)dialog.dismiss();}
}
