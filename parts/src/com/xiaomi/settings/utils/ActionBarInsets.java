/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.utils;

import android.app.ActionBar;
import android.app.Activity;
import android.graphics.Insets;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;

/**
 * The app draws edge to edge, so the content would start under the status bar and the action
 * bar. Pads the content view by both, so the first row of a settings screen stays visible.
 */
public final class ActionBarInsets {

    private ActionBarInsets() {}

    public static void apply(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top + actionBarHeight(activity), bars.right,
                    bars.bottom);
            return WindowInsets.CONSUMED;
        });
        // The action bar has its real height only after the first layout.
        content.post(content::requestApplyInsets);
    }

    private static int actionBarHeight(Activity activity) {
        ActionBar bar = activity.getActionBar();
        if (bar == null || !bar.isShowing()) return 0;
        if (bar.getHeight() > 0) return bar.getHeight();
        TypedValue tv = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            return TypedValue.complexToDimensionPixelSize(tv.data,
                    activity.getResources().getDisplayMetrics());
        }
        return 0;
    }
}
