/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.battery;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;

import com.xiaomi.settings.R;
import com.xiaomi.settings.utils.ActionBarInsets;

/**
 * Battery health: cycle count, how much charge the battery still holds compared with when it
 * was new, and temperature. Everything comes from the sticky ACTION_BATTERY_CHANGED broadcast.
 */
public class BatteryHealthActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        ActionBarInsets.apply(this);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new HealthFragment())
                    .commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    // Extras of ACTION_BATTERY_CHANGED (values in µAh)
    private static final String EXTRA_MAXIMUM_CAPACITY = "android.os.extra.MAXIMUM_CAPACITY";
    private static final String EXTRA_DESIGN_CAPACITY = "android.os.extra.DESIGN_CAPACITY";
    private static final String EXTRA_CYCLE_COUNT = "android.os.extra.CYCLE_COUNT";

    public static class HealthFragment extends PreferenceFragment {
        private Preference mHealth;
        private Preference mCycles;
        private Preference mFull;
        private Preference mDesign;
        private Preference mTemp;

        private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                update(intent);
            }
        };

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            final Context context = getActivity();
            final PreferenceScreen screen =
                    getPreferenceManager().createPreferenceScreen(context);
            mHealth = add(screen, R.string.battery_health_capacity);
            mCycles = add(screen, R.string.battery_health_cycles);
            mFull = add(screen, R.string.battery_health_full);
            mDesign = add(screen, R.string.battery_health_design);
            mTemp = add(screen, R.string.battery_health_temperature);
            final Preference note = new Preference(context);
            note.setSummary(R.string.battery_health_note);
            note.setSelectable(false);
            screen.addPreference(note);
            setPreferenceScreen(screen);
        }

        private static Preference add(PreferenceScreen screen, int title) {
            final Preference pref = new Preference(screen.getContext());
            pref.setTitle(title);
            pref.setSelectable(false);
            screen.addPreference(pref);
            return pref;
        }

        @Override
        public void onResume() {
            super.onResume();
            final Intent sticky = getActivity().registerReceiver(mReceiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) update(sticky);
        }

        @Override
        public void onPause() {
            super.onPause();
            getActivity().unregisterReceiver(mReceiver);
        }

        private void update(Intent intent) {
            final int full = intent.getIntExtra(EXTRA_MAXIMUM_CAPACITY, -1);
            final int design = intent.getIntExtra(EXTRA_DESIGN_CAPACITY, -1);
            final int cycles = intent.getIntExtra(EXTRA_CYCLE_COUNT, -1);
            final int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            final String unknown = getString(R.string.battery_health_unknown);

            mHealth.setSummary(full > 0 && design > 0
                    ? getString(R.string.battery_health_capacity_value,
                            Math.round(full * 100f / design))
                    : unknown);
            mCycles.setSummary(cycles >= 0 ? String.valueOf(cycles) : unknown);
            // The extras are in µAh
            mFull.setSummary(full > 0
                    ? getString(R.string.battery_health_mah, full / 1000) : unknown);
            mDesign.setSummary(design > 0
                    ? getString(R.string.battery_health_mah, design / 1000) : unknown);
            mTemp.setSummary(temp != Integer.MIN_VALUE
                    ? getString(R.string.battery_health_celsius, temp / 10f) : unknown);
        }
    }
}
