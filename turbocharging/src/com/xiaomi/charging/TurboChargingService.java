/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.xiaomi.charging;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;

public class TurboChargingService extends Service {
    private static final String TAG = "TurboCharging";

    private final BroadcastReceiver mPowerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())) {
                Log.i(TAG, "power connected — re-applying turbo settings");
                TurboChargingUtil.applyAllFromPrefs(context);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter f = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        registerReceiver(mPowerReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        TurboChargingUtil.applyAllFromPrefs(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        TurboChargingUtil.applyAllFromPrefs(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(mPowerReceiver); } catch (IllegalArgumentException e) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
