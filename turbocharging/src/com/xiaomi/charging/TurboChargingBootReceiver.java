/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.charging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

public class TurboChargingBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            return;
        }
        TurboChargingUtil.applyAllFromPrefs(context);
        context.startServiceAsUser(
                new Intent(context, TurboChargingService.class), UserHandle.CURRENT);
    }
}
