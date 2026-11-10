/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.backup;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.TimeUnit;

/**
 * Safety net for the one-off Seedvault full backup: switches the backup service back
 * (to Google, usually) a few hours later, so daily backups don't stay off if the user
 * never comes back to switch.
 */
public class TransportRestoreJob extends JobService {
    private static final String TAG = "XiaomiPartsSeedvault";
    private static final int JOB_ID = 0x5eed;
    private static final long DELAY_MS = TimeUnit.HOURS.toMillis(6);

    static void schedule(Context context) {
        final JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, TransportRestoreJob.class))
                .setMinimumLatency(DELAY_MS)
                .setPersisted(true)
                .build();
        context.getSystemService(JobScheduler.class).schedule(job);
    }

    static void cancel(Context context) {
        context.getSystemService(JobScheduler.class).cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            final boolean ok = BackupTransports.restoreSavedTransport(this);
            Log.i(TAG, "Automatic switch back " + (ok ? "done" : "failed"));
            jobFinished(params, !ok);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
