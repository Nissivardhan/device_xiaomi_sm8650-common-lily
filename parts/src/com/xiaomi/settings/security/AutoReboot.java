/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.security;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * Restarts the phone when it has stayed locked for the chosen number of hours, so its data
 * is encrypted again until the PIN is entered. Off by default. The countdown starts when the
 * screen turns off and is cleared on every unlock.
 */
public final class AutoReboot {
    private static final String TAG = "XiaomiPartsAutoReboot";
    public static final String KEY_HOURS = "auto_reboot_hours";
    private static final String KEY_DEADLINE = "auto_reboot_deadline";
    private static final long RETRY_MS = 10 * 60 * 1000L;

    private static boolean sRegistered;

    private AutoReboot() {}

    static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(
                context.createDeviceProtectedStorageContext());
    }

    static int hours(Context context) {
        try {
            return Integer.parseInt(prefs(context).getString(KEY_HOURS, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Called at boot: follow screen-off and unlock events for the rest of this boot. */
    public static synchronized void init(Context context) {
        final Context app = context.getApplicationContext();
        prefs(app).edit().remove(KEY_DEADLINE).apply();
        if (sRegistered) return;
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    cancel(c);
                } else {
                    arm(c);
                }
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED);
        sRegistered = true;
    }

    /** Starts the countdown unless one is already running (more screen-offs don't extend it). */
    static void arm(Context context) {
        final int hours = hours(context);
        final KeyguardManager km = context.getSystemService(KeyguardManager.class);
        if (hours <= 0 || km == null || !km.isDeviceSecure()) return;
        final SharedPreferences prefs = prefs(context);
        if (prefs.getLong(KEY_DEADLINE, 0) > SystemClock.elapsedRealtime()) return;
        final long deadline = SystemClock.elapsedRealtime() + hours * 3_600_000L;
        prefs.edit().putLong(KEY_DEADLINE, deadline).apply();
        schedule(context, deadline);
        Log.i(TAG, "Restart in " + hours + " h unless unlocked");
    }

    /** Clears the countdown: after an unlock, or when the option is turned off. */
    static void cancel(Context context) {
        prefs(context).edit().remove(KEY_DEADLINE).apply();
        context.getSystemService(AlarmManager.class).cancel(pendingIntent(context));
    }

    private static void schedule(Context context, long when) {
        context.getSystemService(AlarmManager.class).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, when, pendingIntent(context));
    }

    private static PendingIntent pendingIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0,
                new Intent(context, AutoRebootReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The countdown ran out: restart if the phone is still locked and not in a call. */
    static void onDeadline(Context context) {
        final KeyguardManager km = context.getSystemService(KeyguardManager.class);
        if (hours(context) <= 0 || km == null || !km.isDeviceLocked()) {
            cancel(context);
            return;
        }
        final AudioManager am = context.getSystemService(AudioManager.class);
        if (am != null && am.getMode() != AudioManager.MODE_NORMAL) {
            Log.i(TAG, "In a call, trying again in 10 min");
            schedule(context, SystemClock.elapsedRealtime() + RETRY_MS);
            return;
        }
        Log.i(TAG, "Locked for " + hours(context) + " h, restarting");
        prefs(context).edit().remove(KEY_DEADLINE).commit();
        context.getSystemService(PowerManager.class).reboot("auto_reboot");
    }
}
