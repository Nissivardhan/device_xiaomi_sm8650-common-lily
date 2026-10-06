/*
 * Copyright (C) 2023 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Display.HdrCapabilities;
import vendor.xiaomi.hw.touchfeature.ITouchFeature;

import com.xiaomi.settings.display.ColorModeService;
import com.xiaomi.settings.display.DcDimming;
import com.xiaomi.settings.display.DozeBrightnessService;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "XiaomiParts";
    private static final boolean DEBUG = false;
    private static final int DOUBLE_TAP_TO_WAKE_MODE = 14;
    private static final int TOUCH_FOD_ENABLE_MODE = 10;
    private static final int TOUCH_AOD_ENABLE_MODE = 11;
    private static final int TOUCH_FODICON_ENABLE_MODE = 16;

    private ITouchFeature xiaomiTouchFeatureAidl;

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (!intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            return;
        }
        if (DEBUG)
            Log.d(TAG, "Received boot completed intent");

        // Display
        context.startServiceAsUser(new Intent(context, ColorModeService.class),
                UserHandle.CURRENT);
        context.startServiceAsUser(new Intent(context, DozeBrightnessService.class),
                UserHandle.CURRENT);
        DcDimming.apply(context);

        // Override HDR types to enable Dolby Vision
        final DisplayManager displayManager = context.getSystemService(DisplayManager.class);
        displayManager.overrideHdrTypes(Display.DEFAULT_DISPLAY,
                new int[] {HdrCapabilities.HDR_TYPE_DOLBY_VISION, HdrCapabilities.HDR_TYPE_HDR10,
                        HdrCapabilities.HDR_TYPE_HLG, HdrCapabilities.HDR_TYPE_HDR10_PLUS});

        ContentObserver observer = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                updateTapToWakeStatus(context);
            }
        };

        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOUBLE_TAP_TO_WAKE), true, observer);

        updateTapToWakeStatus(context);

        // Screen off UDFPS (always on)
        enableScreenOffUdfps();

        // Circle to Search on navigation handle / home long press, on by default like on Pixels
        enableSearchEntrypointsByDefault(context);
    }

    private void enableSearchEntrypointsByDefault(Context context) {
        final ContentResolver resolver = context.getContentResolver();
        for (String key : new String[] {"search_all_entrypoints_enabled",
                "search_press_hold_nav_handle_enabled", "search_long_press_home_enabled"}) {
            // Only when never set, so a user who turned it off keeps it off
            if (Settings.Secure.getString(resolver, key) == null) {
                Settings.Secure.putInt(resolver, key, 1);
            }
        }
    }

    private void initTouchFeature() {
        if (xiaomiTouchFeatureAidl == null) {
            try {
                var name = "default";
                var fqName =
                        vendor.xiaomi.hw.touchfeature.ITouchFeature.DESCRIPTOR + "/" + name;
                var binder = android.os.Binder.allowBlocking(
                        android.os.ServiceManager.waitForDeclaredService(fqName));
                xiaomiTouchFeatureAidl =
                        vendor.xiaomi.hw.touchfeature.ITouchFeature.Stub.asInterface(binder);
            } catch (Exception e) {
                Log.e(TAG, "Failed to initialize Touch Feature service", e);
            }
        }
    }

    private void updateTapToWakeStatus(Context context) {
        try {
            initTouchFeature();

            boolean enabled = Settings.Secure.getInt(context.getContentResolver(),
                                      Settings.Secure.DOUBLE_TAP_TO_WAKE, 0)
                    == 1;
            xiaomiTouchFeatureAidl.setModeValue(0, DOUBLE_TAP_TO_WAKE_MODE, enabled ? 1 : 0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to update Tap to Wake status", e);
        }
    }

    private void enableScreenOffUdfps() {
        try {
            initTouchFeature();

            xiaomiTouchFeatureAidl.setModeValue(0, TOUCH_FOD_ENABLE_MODE, 1);
            xiaomiTouchFeatureAidl.setModeValue(0, TOUCH_AOD_ENABLE_MODE, 1);
            xiaomiTouchFeatureAidl.setModeValue(0, TOUCH_FODICON_ENABLE_MODE, 1);
        } catch (Exception e) {
            Log.e(TAG, "Failed to enable screen off UDFPS", e);
        }
    }
}
