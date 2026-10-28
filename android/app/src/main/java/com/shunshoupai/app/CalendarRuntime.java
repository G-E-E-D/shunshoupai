package com.shunshoupai.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import java.util.HashSet;
import java.util.Set;

/** One calendar, listener socket and sync worker per process, shared by UI, service and jobs. */
final class CalendarRuntime {
    private static CalendarRuntime instance;
    static synchronized CalendarRuntime get(Context context) {
        if (instance == null) instance = new CalendarRuntime(context.getApplicationContext());
        return instance;
    }
    final Context context;
    final LocalScheduleStore store;
    final DeviceHostRuntime host;
    final ScheduleSync sync;
    final ReminderScheduler reminders;
    final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Object> visible = new HashSet<>();
    private final Set<Runnable> listeners = new HashSet<>();
    private final PowerManager.WakeLock transferLock;
    private boolean service, job, closed;
    private int failures;
    private long retryAfter;
    private String message = "日程保存在本机";
    private final Runnable tick = new Runnable() { public void run() {
        if (!active()) return;
        attemptSync(false); handler.postDelayed(this, visible.isEmpty() ? 30_000 : 15_000);
    }};
    private CalendarRuntime(Context context) {
        this.context = context;
        store = new LocalScheduleStore(context);
        store.initialize(context.getSharedPreferences("connection", Context.MODE_PRIVATE).getString("server_url", ""));
        transferLock = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Shunshoupai:peer-transfer");
        transferLock.setReferenceCounted(false);
        reminders = new ReminderScheduler(context, store, ignored -> {});
        host = new DeviceHostRuntime(context, store, handler, this::received);
        sync = new ScheduleSync(context, store, handler, host, this::completed);
    }
    private void completed(String origin, String scope, String result, boolean pairing) {
        releaseTransfer(); message = result;
        failures = sync.connectedLast() ? 0 : Math.min(5, failures + 1);
        retryAfter = failures == 0 ? 0 : android.os.SystemClock.elapsedRealtime() + Math.min(900_000L, 30_000L << failures);
        received();
    }
    void listen(Runnable listener) { listeners.add(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); disposeUnused(); }
    String status() { return message; }
    boolean isVisible() { return !visible.isEmpty(); }
    private boolean active() { return !closed && (isVisible() || service || job); }
    boolean hasPeers() {
        try { for (org.json.JSONObject peer : store.peers()) if (peer.optBoolean("enabled")) return true; }
        catch (Exception ignored) { }
        return false;
    }
    void visible(Object owner, boolean value) {
        if (value) visible.add(owner); else visible.remove(owner);
        if (value) { reminders.refresh(); maintainBackground(); }
        updateNetworking();
    }
    void service(boolean value) { service = value; updateNetworking(); if (!value) disposeUnused(); }
    void job(boolean value) { job = value; updateNetworking(); if (!value) disposeUnused(); }
    void maintainBackground() {
        if (closed) return;
        SyncMaintenance.schedule(context, BackgroundSyncService.enabled(context) && hasPeers());
        if (!BackgroundSyncService.enabled(context) || !hasPeers()) BackgroundSyncService.stop(context);
        else if (isVisible()) BackgroundSyncService.ensureStarted(context);
    }
    private void updateNetworking() {
        handler.removeCallbacks(tick);
        if (active()) {
            try { host.start(); } catch (Exception ignored) { message = "本机日程可用，局域网接收暂未开启，将自动重试"; }
            handler.post(tick);
        } else {
            sync.invalidate(); host.stop(); releaseTransfer();
        }
    }
    void requestSync() { attemptSync(true); }
    private void attemptSync(boolean immediate) {
        reminders.refresh();
        if (!active()) return;
        if (!immediate && !isVisible() && android.os.SystemClock.elapsedRealtime() < retryAfter) return;
        try { host.start(); } catch (Exception ignored) { return; }
        if (!hasPeers() || sync.isBusy()) return;
        transferLock.acquire(180_000);
        sync.sync();
        if (!sync.isBusy()) releaseTransfer();
    }
    void localChanged() { maintainBackground(); requestSync(); }
    private void received() {
        if (closed) return;
        reminders.refresh(); maintainBackground();
        for (Runnable listener : new HashSet<>(listeners)) listener.run();
    }
    private void releaseTransfer() { if (transferLock.isHeld()) transferLock.release(); }
    static synchronized void disposeUnused() {
        if (instance == null || instance.active() || !instance.listeners.isEmpty()) return;
        CalendarRuntime old = instance; instance = null; old.closed = true;
        old.handler.removeCallbacksAndMessages(null); old.host.stop(); old.releaseTransfer(); old.reminders.close(); old.sync.close();
    }
}
