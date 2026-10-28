package com.shunshoupai.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

/** A visible, user-stoppable connection to already paired LAN devices. Never carries model data. */
public final class BackgroundSyncService extends Service {
    static final String CHANNEL = "peer-background-sync";
    static final String STOP = "com.shunshoupai.app.STOP_BACKGROUND_SYNC";
    private static final int NOTIFICATION = 2401;
    private static volatile boolean running;
    private static long starting;
    private CalendarRuntime runtime;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    static boolean enabled(Context context) { return context.getSharedPreferences("background-operation", MODE_PRIVATE).getBoolean("sync", true); }
    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("background-operation", MODE_PRIVATE).edit().putBoolean("sync", enabled).apply();
        CalendarRuntime.get(context).maintainBackground();
    }
    static boolean isRunning() { return running; }
    static synchronized void ensureStarted(Context context) {
        if (running || (starting != 0 && SystemClock.elapsedRealtime() - starting < 5_000) || !enabled(context)) return;
        starting = SystemClock.elapsedRealtime();
        try { context.startForegroundService(new Intent(context, BackgroundSyncService.class)); }
        catch (RuntimeException denied) { starting = 0; /* Persisted job and next visible launch remain available. */ }
    }
    static void stop(Context context) { context.stopService(new Intent(context, BackgroundSyncService.class)); }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "设备自动同步", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("在后台连接已配对的局域网设备；可以随时暂停"); channel.setShowBadge(false);
        notifications.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 2401, new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_BACKGROUND_SETTINGS), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause = PendingIntent.getService(this, 2402, new Intent(this, BackgroundSyncService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_sync)
            .setContentTitle("顺手排 · 自动同步已开启").setContentText("已配对设备可达时合并日程，无需保持页面打开")
            .setContentIntent(open).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(null, "暂停后台同步", pause).build()).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION, notification);
        running = true; runtime = CalendarRuntime.get(this);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            setEnabled(this, false); stopSelf(); return START_NOT_STICKY;
        }
        if (!enabled(this) || !runtime.hasPeers()) { stopSelf(); return START_NOT_STICKY; }
        runtime.service(true);
        if (callback == null) {
            connectivity = getSystemService(ConnectivityManager.class);
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { runtime.handler.post(runtime::requestSync); }
                @Override public void onLost(Network network) { runtime.handler.post(runtime::requestSync); }
            };
            try { connectivity.registerNetworkCallback(new NetworkRequest.Builder().build(), callback); }
            catch (RuntimeException ignored) { callback = null; }
        }
        return START_STICKY;
    }
    @Override public void onDestroy() {
        running = false; starting = 0;
        if (connectivity != null && callback != null) connectivity.unregisterNetworkCallback(callback);
        if (runtime != null) runtime.service(false);
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
