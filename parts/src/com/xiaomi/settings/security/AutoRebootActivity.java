/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.security;

import android.app.Activity;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;

import com.xiaomi.settings.R;

/** Settings › System › Auto restart: Off (default) or a number of hours locked. */
public class AutoRebootActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new AutoRebootFragment())
                    .commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    public static class AutoRebootFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getPreferenceManager().setStorageDeviceProtected();
            final PreferenceScreen screen =
                    getPreferenceManager().createPreferenceScreen(getActivity());

            final ListPreference list = new ListPreference(getActivity());
            list.setKey(AutoReboot.KEY_HOURS);
            list.setTitle(R.string.auto_reboot_title);
            list.setDialogTitle(R.string.auto_reboot_title);
            list.setEntries(R.array.auto_reboot_entries);
            list.setEntryValues(R.array.auto_reboot_values);
            list.setDefaultValue("0");
            list.setSummary("%s");
            list.setOnPreferenceChangeListener((Preference p, Object value) -> {
                // A new choice applies from the next lock
                AutoReboot.cancel(getActivity());
                return true;
            });
            screen.addPreference(list);

            final Preference note = new Preference(getActivity());
            note.setSummary(R.string.auto_reboot_note);
            note.setSelectable(false);
            screen.addPreference(note);
            setPreferenceScreen(screen);
        }
    }
}
