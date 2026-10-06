/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.display;

import static com.xiaomi.settings.display.DfWrapper.DfParams;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Anti-flicker (DC dimming): the panel dims by lowering its drive current instead of PWM, which
 * removes low brightness flicker. Switched through displayfeature like on stock.
 */
public final class DcDimming {
    private static final String TAG = "XiaomiPartsDcDimming";

    private static final String PREFS = "dc_dimming";
    private static final String KEY_ENABLED = "enabled";

    /* displayfeature DC_BACKLIGHT_STATE */
    private static final int DC_BACKLIGHT_STATE = 20;

    private DcDimming() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
        apply(context);
    }

    public static void apply(Context context) {
        final boolean enabled = isEnabled(context);
        Log.d(TAG, "apply: enabled=" + enabled);
        DfWrapper.setDisplayFeature(new DfParams(DC_BACKLIGHT_STATE, enabled ? 1 : 0, 0));
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
