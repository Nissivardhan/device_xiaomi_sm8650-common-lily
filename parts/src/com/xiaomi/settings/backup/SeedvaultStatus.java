/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.backup;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;

import com.xiaomi.settings.R;

/**
 * Status line for Seedvault in Settings › System: Seedvault reports each backup's result
 * ({@link Receiver}) and Settings asks {@link Provider} for the summary to show.
 */
public final class SeedvaultStatus {
    public static final String ACTION_RESULT = "com.xiaomi.settings.action.SEEDVAULT_BACKUP_RESULT";
    private static final String KEY_TIME = "seedvault_last_backup_time";
    private static final String KEY_OK = "seedvault_last_backup_ok";

    private SeedvaultStatus() {}

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(
                context.createDeviceProtectedStorageContext());
    }

    static String summary(Context context) {
        final SharedPreferences prefs = prefs(context);
        final long time = prefs.getLong(KEY_TIME, 0);
        if (time == 0) return context.getString(R.string.seedvault_status_never);
        final CharSequence when = DateUtils.getRelativeTimeSpanString(time,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        return context.getString(prefs.getBoolean(KEY_OK, false)
                ? R.string.seedvault_status_ok : R.string.seedvault_status_failed, when);
    }

    /** Sent by Seedvault (holding the BACKUP permission) after each backup. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_RESULT.equals(intent.getAction())) return;
            prefs(context).edit()
                    .putLong(KEY_TIME, intent.getLongExtra("time", System.currentTimeMillis()))
                    .putBoolean(KEY_OK, intent.getBooleanExtra("success", false))
                    .apply();
        }
    }

    /** Settings reads the injected tile's summary through call("getSummary"). */
    public static class Provider extends ContentProvider {
        private static final String METHOD_GET_SUMMARY = "getSummary";
        private static final String META_DATA_SUMMARY = "com.android.settings.summary";

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Bundle call(String method, String arg, Bundle extras) {
            if (!METHOD_GET_SUMMARY.equals(method)) return null;
            final Bundle bundle = new Bundle();
            bundle.putString(META_DATA_SUMMARY, summary(getContext()));
            return bundle;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args,
                String sortOrder) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }
    }
}
