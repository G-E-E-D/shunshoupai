package com.shunshoupai.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;

/** Persisted, bounded fallback if the continuous peer service is reclaimed by the OS. */
public final class SyncMaintenance extends JobService {
    static final int JOB = 2403;
    private CalendarRuntime runtime;
    private Runnable finish;
    static void schedule(Context context, boolean enabled) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!enabled) { scheduler.cancel(JOB); return; }
        if (scheduler.getPendingJob(JOB) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, SyncMaintenance.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15 * 60_000)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        runtime = CalendarRuntime.get(this);
        if (!BackgroundSyncService.enabled(this) || !runtime.hasPeers()) return false;
        runtime.job(true); runtime.requestSync();
        long deadline = SystemClock.elapsedRealtime() + 150_000;
        finish = new Runnable() { @Override public void run() {
            if (!runtime.sync.isBusy() || SystemClock.elapsedRealtime() >= deadline) {
                runtime.job(false); finish = null; jobFinished(parameters, false);
            } else runtime.handler.postDelayed(this, 1000);
        }};
        runtime.handler.postDelayed(finish, 1000); return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        if (runtime != null) { if (finish != null) runtime.handler.removeCallbacks(finish); runtime.job(false); }
        finish = null; return BackgroundSyncService.enabled(this);
    }
}
