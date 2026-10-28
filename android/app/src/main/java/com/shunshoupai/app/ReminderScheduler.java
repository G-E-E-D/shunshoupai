package com.shunshoupai.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Matches desktop's opt-in foreground reminder; no remote device or background permission is needed. */
final class ReminderScheduler {
    private final LocalScheduleStore store;
    private final SharedPreferences preferences;
    private final Consumer<String> onNotice;
    private final LongSupplier now;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean foreground, closed;
    private final Runnable tick = new Runnable() { @Override public void run() { if (!foreground || closed) return; refresh(); handler.postDelayed(this, 15_000); } };

    ReminderScheduler(Context context, LocalScheduleStore store, Consumer<String> onNotice) { this(context, store, onNotice, System::currentTimeMillis); }
    ReminderScheduler(Context context, LocalScheduleStore store, Consumer<String> onNotice, LongSupplier now) {
        this.store = store; this.onNotice = onNotice; this.now = now;
        preferences = context.getSharedPreferences("foreground-reminders-" + store.deviceId(), Context.MODE_PRIVATE);
    }
    boolean isEnabled() { return preferences.getBoolean("enabled", false); }
    void setEnabled(boolean value) { if (closed) return; preferences.edit().putBoolean("enabled", value).apply(); if (value) refresh(); }
    void onForeground(boolean value) { if (closed) return; foreground = value; handler.removeCallbacks(tick); if (value) { refresh(); handler.postDelayed(tick, 15_000); } }
    void refresh() {
        if (closed || !foreground || !isEnabled()) return;
        long current = now.getAsLong(); Set<String> seen = new LinkedHashSet<>(preferences.getStringSet("shown", java.util.Collections.emptySet()));
        seen.removeIf(value -> { try { return Long.parseLong(value.substring(value.lastIndexOf('|') + 1)) < current - 86_400_000; } catch (RuntimeException malformed) { return true; } });
        List<String> titles = new ArrayList<>();
        try {
            for (JSONObject event : store.list(store.primaryScope())) {
                if (event.optBoolean("deleted") || !"planned".equals(event.optString("status")) || (event.optJSONArray("replica_conflicts") != null && event.getJSONArray("replica_conflicts").length() > 1)) continue;
                long start = LocalDateTime.parse(event.getString("start_local"), LocalScheduleStore.LOCAL).atZone(LocalScheduleStore.ZONE).toInstant().toEpochMilli();
                String key = event.getString("id") + "|" + start;
                if (start >= current && start - current <= 300_000 && seen.add(key)) titles.add(event.getString("title"));
            }
            preferences.edit().putStringSet("shown", new LinkedHashSet<>(seen)).apply();
            if (!titles.isEmpty()) onNotice.accept("即将开始：" + String.join("、", titles.subList(0, Math.min(3, titles.size()))) + (titles.size() > 3 ? "等 " + titles.size() + " 项" : ""));
        } catch (Exception unavailable) { /* Local calendar recovery owns storage errors; a reminder must never block editing. */ }
    }
    void close() { closed = true; foreground = false; handler.removeCallbacks(tick); }
}
