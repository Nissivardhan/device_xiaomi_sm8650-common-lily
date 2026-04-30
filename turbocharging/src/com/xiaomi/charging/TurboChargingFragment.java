/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.charging;

import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.MainSwitchPreference;

public class TurboChargingFragment extends PreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    private MainSwitchPreference mTurbo;
    private SwitchPreferenceCompat mSports;
    private ListPreference mCurrent;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.turbocharging, rootKey);

        mTurbo   = findPreference(Constants.PREF_TURBO_ENABLED);
        mSports  = findPreference(Constants.PREF_SPORTS_MODE);
        mCurrent = findPreference(Constants.PREF_TURBO_CURRENT);

        mTurbo.setOnPreferenceChangeListener(this);
        mSports.setOnPreferenceChangeListener(this);
        mCurrent.setOnPreferenceChangeListener(this);

        boolean on = mTurbo.isChecked();
        mSports.setEnabled(on);
        mCurrent.setEnabled(on);
    }

    @Override
    public boolean onPreferenceChange(Preference pref, Object newValue) {
        if (pref == mTurbo) {
            boolean on = (Boolean) newValue;
            mSports.setEnabled(on);
            mCurrent.setEnabled(on);
            if (!on) mSports.setChecked(false);
            TurboChargingUtil.applyTurbo(getContext(), on, mCurrent.getValue());
            TurboChargingUtil.applySportsMode(getContext(), on && mSports.isChecked());
            Toast.makeText(getContext(),
                    on ? R.string.toast_turbo_on : R.string.toast_turbo_off,
                    Toast.LENGTH_SHORT).show();
            return true;
        } else if (pref == mSports) {
            boolean on = (Boolean) newValue;
            TurboChargingUtil.applySportsMode(getContext(), on && mTurbo.isChecked());
            Toast.makeText(getContext(),
                    on ? R.string.toast_sports_on : R.string.toast_sports_off,
                    Toast.LENGTH_SHORT).show();
            return true;
        } else if (pref == mCurrent) {
            String value = (String) newValue;
            TurboChargingUtil.applyTurbo(getContext(), mTurbo.isChecked(), value);
            int idx = mCurrent.findIndexOfValue(value);
            CharSequence label = idx >= 0 ? mCurrent.getEntries()[idx] : value;
            Toast.makeText(getContext(),
                    getString(R.string.toast_wattage_set, label),
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        return true;
    }
}
