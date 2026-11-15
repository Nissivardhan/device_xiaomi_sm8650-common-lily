/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.security;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Alarm set by {@link AutoReboot} when the phone was locked. */
public class AutoRebootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AutoReboot.onDeadline(context);
    }
}
