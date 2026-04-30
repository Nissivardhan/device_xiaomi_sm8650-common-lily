/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.charging;

import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

public class TurboChargingTile extends TileService {
    @Override
    public void onClick() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean newState = !prefs.getBoolean(Constants.PREF_TURBO_ENABLED, false);
        prefs.edit().putBoolean(Constants.PREF_TURBO_ENABLED, newState).apply();
        TurboChargingUtil.applyAllFromPrefs(this);
        updateTile();
        Toast.makeText(this,
                getString(newState ? R.string.toast_turbo_on : R.string.toast_turbo_off),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(Constants.PREF_TURBO_ENABLED, false);
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
