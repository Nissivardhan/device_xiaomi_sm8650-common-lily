/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.ServiceManager;
import android.util.Log;

import vendor.xiaomi.hw.touchfeature.ITouchFeature;

/**
 * High touch polling rate: the touch IC's game mode raises the report rate from about 120 Hz to
 * about 240 Hz. Off by default, as it may also change how the IC filters touches.
 */
public final class HighTouchPolling {
    private static final String TAG = "XiaomiPartsTouchPolling";

    private static final String PREFS = "high_touch_polling";
    private static final String KEY_ENABLED = "enabled";

    /* touchfeature Touch_Game_Mode */
    private static final int TOUCH_GAME_MODE = 0;

    private HighTouchPolling() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        setGameMode(enabled);
    }

    /** Sets game mode again if the toggle is on, for when the IC may have lost it. */
    public static void reapply(Context context) {
        if (isEnabled(context)) {
            setGameMode(true);
        }
    }

    private static void setGameMode(boolean enabled) {
        try {
            final String name = ITouchFeature.DESCRIPTOR + "/default";
            final ITouchFeature touchFeature = ITouchFeature.Stub.asInterface(
                    Binder.allowBlocking(ServiceManager.waitForDeclaredService(name)));
            touchFeature.setModeValue(0, TOUCH_GAME_MODE, enabled ? 1 : 0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set touch game mode", e);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
