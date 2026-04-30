/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.charging;

import android.os.Bundle;

import androidx.preference.PreferenceManager;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public class TurboChargingActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.turbocharging, false);
        setContentView(R.layout.turbocharging_layout);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, new TurboChargingFragment())
                .commit();
    }
}
