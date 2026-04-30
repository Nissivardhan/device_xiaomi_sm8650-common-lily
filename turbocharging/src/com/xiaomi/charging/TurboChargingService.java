/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.charging;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.UEventObserver;
import android.util.Log;

public class TurboChargingService extends Service {
    private static final String TAG = "TurboCharging";
    private UEventObserver mObserver;

    @Override
    public void onCreate() {
        super.onCreate();
        mObserver = new UEventObserver() {
            @Override
            public void onUEvent(UEvent event) {
                String online = event.get("POWER_SUPPLY_ONLINE");
                if ("1".equals(online)) {
                    Log.i(TAG, "USB online — re-applying turbo settings");
                    TurboChargingUtil.applyAllFromPrefs(TurboChargingService.this);
                }
            }
        };
        mObserver.startObserving("DEVPATH=/sys/class/power_supply/usb");
        TurboChargingUtil.applyAllFromPrefs(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TurboChargingUtil.applyAllFromPrefs(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mObserver != null) mObserver.stopObserving();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
