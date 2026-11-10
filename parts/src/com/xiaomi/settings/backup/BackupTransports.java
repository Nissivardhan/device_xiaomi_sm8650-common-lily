/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.backup;

import android.app.backup.BackupManager;
import android.app.backup.SelectBackupTransportCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Switches Android's backup service between Seedvault and the one in use before. */
final class BackupTransports {
    private static final String TAG = "XiaomiPartsSeedvault";

    static final String SEEDVAULT_PACKAGE = "com.stevesoltys.seedvault";
    private static final ComponentName SEEDVAULT_TRANSPORT = new ComponentName(
            SEEDVAULT_PACKAGE, "com.stevesoltys.seedvault.transport.ConfigurableBackupTransportService");

    private static final String PREFS = "seedvault_full_backup";
    private static final String KEY_SAVED = "saved_transport";

    private BackupTransports() {}

    static boolean hasSeedvault(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SEEDVAULT_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean isSeedvaultActive(Context context) {
        return SEEDVAULT_TRANSPORT.equals(current(context));
    }

    static boolean hasSavedTransport(Context context) {
        return prefs(context).getString(KEY_SAVED, null) != null;
    }

    /** Remembers the current backup service, then makes Seedvault the active one. */
    static boolean switchToSeedvault(Context context) {
        final ComponentName previous = current(context);
        if (previous != null && !SEEDVAULT_TRANSPORT.equals(previous)) {
            prefs(context).edit().putString(KEY_SAVED, previous.flattenToString()).apply();
        }
        return select(context, SEEDVAULT_TRANSPORT);
    }

    /** Puts back the backup service saved by {@link #switchToSeedvault}, if any. */
    static boolean restoreSavedTransport(Context context) {
        final String saved = prefs(context).getString(KEY_SAVED, null);
        if (saved == null) return true;
        final ComponentName target = ComponentName.unflattenFromString(saved);
        if (target == null) {
            prefs(context).edit().remove(KEY_SAVED).apply();
            return false;
        }
        // Only switch back if Seedvault is still the active one; respect a manual change
        final boolean ok = !isSeedvaultActive(context) || select(context, target);
        if (ok) prefs(context).edit().remove(KEY_SAVED).apply();
        return ok;
    }

    private static ComponentName current(Context context) {
        try {
            return new BackupManager(context).getCurrentTransportComponent();
        } catch (Exception e) {
            Log.e(TAG, "Could not read the current backup service", e);
            return null;
        }
    }

    private static boolean select(Context context, ComponentName transport) {
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] success = {false};
        try {
            new BackupManager(context).selectBackupTransport(transport,
                    new SelectBackupTransportCallback() {
                        @Override
                        public void onSuccess(String transportName) {
                            success[0] = true;
                            done.countDown();
                        }

                        @Override
                        public void onFailure(int reason) {
                            Log.e(TAG, "Switching to " + transport + " failed: " + reason);
                            done.countDown();
                        }
                    });
            done.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "Switching to " + transport + " failed", e);
        }
        Log.i(TAG, "Backup service " + transport.flattenToShortString()
                + (success[0] ? " selected" : " NOT selected"));
        return success[0];
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
