/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.charging;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public final class TurboChargingUtil {
    private static final String TAG = "TurboCharging";

    private TurboChargingUtil() {}

    public static void applyTurbo(Context ctx, boolean enabled, String wattValue) {
        String value = enabled ? wattValue : Constants.DEFAULT_OFF_VALUE;
        writeNode(Constants.CHARGE_CURRENT_NODE, value);
        Log.i(TAG, "applyTurbo enabled=" + enabled + " -> " + value);
    }

    public static void applySportsMode(Context ctx, boolean enabled) {
        writeNode(Constants.SPORTS_MODE_NODE, enabled ? "1" : "0");
        Log.i(TAG, "applySportsMode -> " + (enabled ? "1" : "0"));
    }

    public static void applyAllFromPrefs(Context ctx) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);
        boolean enabled = p.getBoolean(Constants.PREF_TURBO_ENABLED, true);
        String watt    = p.getString(Constants.PREF_TURBO_CURRENT, Constants.DEFAULT_ON_VALUE);
        boolean sports = p.getBoolean(Constants.PREF_SPORTS_MODE, false) && enabled;
        applyTurbo(ctx, enabled, watt);
        applySportsMode(ctx, sports);
    }

    private static void writeNode(String path, String value) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(path))) {
            w.write(value);
        } catch (IOException e) {
            Log.w(TAG, "writeNode " + path + " failed: " + e.getMessage());
        }
    }
}
