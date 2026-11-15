/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.battery;

import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.xiaomi.settings.R;

/**
 * Quick settings tile for Settings › Battery › Charging control: turns it on or off and
 * shows the mode. The charging control service reads the same settings.
 */
public class ChargingControlTileService extends TileService {
    private static final String KEY_ENABLED = "charging_control_enabled";
    private static final String KEY_MODE = "charging_control_mode";
    private static final String KEY_LIMIT = "charging_control_limit";
    private static final int MODE_AUTO = 1;
    private static final int MODE_CUSTOM = 2;
    private static final int MODE_LIMIT = 3;

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        Settings.System.putInt(getContentResolver(), KEY_ENABLED, isEnabled() ? 0 : 1);
        updateTile();
    }

    private boolean isEnabled() {
        return Settings.System.getInt(getContentResolver(), KEY_ENABLED, 0) == 1;
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) return;
        final boolean enabled = isEnabled();
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (enabled) {
            final int mode = Settings.System.getInt(getContentResolver(), KEY_MODE, MODE_AUTO);
            final int limit = Settings.System.getInt(getContentResolver(), KEY_LIMIT, 80);
            switch (mode) {
                case MODE_LIMIT:
                    tile.setSubtitle(getString(R.string.charging_control_tile_limit, limit));
                    break;
                case MODE_CUSTOM:
                    tile.setSubtitle(getString(R.string.charging_control_tile_custom));
                    break;
                default:
                    tile.setSubtitle(getString(R.string.charging_control_tile_auto));
                    break;
            }
        } else {
            tile.setSubtitle(getString(R.string.charging_control_tile_off));
        }
        tile.updateTile();
    }
}
