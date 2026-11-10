/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.backup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.util.function.Consumer;

import com.xiaomi.settings.R;

/**
 * Opens Seedvault, and offers a one-off full backup with it.
 *
 * Android has one active backup service. On builds with GMS that is Google's, so
 * Seedvault never receives app data. "Full backup" switches the backup service to
 * Seedvault for a while; the next time the icon is opened it offers to switch back,
 * and {@link TransportRestoreJob} switches back on its own after a few hours.
 */
public class SeedvaultLauncherActivity extends Activity {
    private static final String TAG = "XiaomiPartsSeedvault";

    private static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;

    private static final ComponentName SEEDVAULT_SETTINGS = new ComponentName(
            "com.stevesoltys.seedvault", "com.stevesoltys.seedvault.settings.SettingsActivity");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!BackupTransports.hasSeedvault(this)) {
            openSeedvault();
            return;
        }

        if (BackupTransports.isSeedvaultActive(this)) {
            if (BackupTransports.hasSavedTransport(this)) {
                showSwitchBackDialog();
            } else {
                // Seedvault was chosen as the daily backup on purpose; nothing to offer
                openSeedvault();
            }
        } else {
            showFullBackupDialog();
        }
    }

    private void showFullBackupDialog() {
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(R.string.seedvault_full_backup_title)
                .setMessage(R.string.seedvault_full_backup_message)
                .setPositiveButton(R.string.seedvault_full_backup_start, (d, w) ->
                        inBackground(() -> BackupTransports.switchToSeedvault(this), ok -> {
                            if (ok) TransportRestoreJob.schedule(this);
                            Toast.makeText(this, ok ? R.string.seedvault_full_backup_hint
                                    : R.string.seedvault_switch_failed, Toast.LENGTH_LONG).show();
                            openSeedvault();
                        }))
                .setNeutralButton(R.string.seedvault_just_open, (d, w) -> openSeedvault())
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void showSwitchBackDialog() {
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(R.string.seedvault_switch_back_title)
                .setMessage(R.string.seedvault_switch_back_message)
                .setPositiveButton(R.string.seedvault_switch_back, (d, w) ->
                        inBackground(() -> BackupTransports.restoreSavedTransport(this), ok -> {
                            if (ok) TransportRestoreJob.cancel(this);
                            Toast.makeText(this, ok ? R.string.seedvault_switched_back
                                    : R.string.seedvault_switch_failed, Toast.LENGTH_LONG).show();
                            finish();
                        }))
                .setNeutralButton(R.string.seedvault_just_open, (d, w) -> openSeedvault())
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    /**
     * Switching the backup service waits for a callback that arrives on the main
     * thread, so it must run on another thread; the result comes back on the main one.
     */
    private void inBackground(java.util.function.BooleanSupplier work, Consumer<Boolean> then) {
        new Thread(() -> {
            final boolean ok = work.getAsBoolean();
            runOnUiThread(() -> then.accept(ok));
        }).start();
    }

    private void openSeedvault() {
        try {
            // Start Seedvault in a fresh task. Bringing back an old one could show a
            // window that was still closing, which left a black screen.
            startActivity(new Intent().setComponent(SEEDVAULT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Seedvault", e);
        }
        finish();
    }

    /** Hides the launcher icon on builds without Seedvault. */
    public static void updateEnabled(Context context) {
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, SeedvaultLauncherActivity.class),
                BackupTransports.hasSeedvault(context)
                        ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
