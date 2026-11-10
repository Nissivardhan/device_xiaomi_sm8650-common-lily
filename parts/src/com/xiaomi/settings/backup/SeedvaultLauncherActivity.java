/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.backup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Opens Seedvault's settings. With GMS the active backup transport is Google's, so
 * Settings > System > Backup leads there and Seedvault has no entry point of its own.
 */
public class SeedvaultLauncherActivity extends Activity {
    private static final String TAG = "XiaomiPartsSeedvault";

    private static final ComponentName SEEDVAULT_SETTINGS = new ComponentName(
            "com.stevesoltys.seedvault", "com.stevesoltys.seedvault.settings.SettingsActivity");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        final PackageManager pm = context.getPackageManager();
        boolean installed;
        try {
            pm.getPackageInfo(SEEDVAULT_SETTINGS.getPackageName(), 0);
            installed = true;
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        }
        pm.setComponentEnabledSetting(
                new ComponentName(context, SeedvaultLauncherActivity.class),
                installed ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
