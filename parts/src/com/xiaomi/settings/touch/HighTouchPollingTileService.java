/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class HighTouchPollingTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        HighTouchPolling.setEnabled(this, !HighTouchPolling.isEnabled(this));
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(HighTouchPolling.isEnabled(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
