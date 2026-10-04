/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.display;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Drives the panel's own AOD brightness while dozing. In DOZE/DOZE_SUSPEND the
 * panel ignores the regular backlight and only honours doze_brightness, which
 * the kernel resets to "normal" on every doze transition. The panel has three idle-mode
 * levels (register 0x6d), the kernel only exposes two, so the level is also set directly.
 */
public class DozeBrightnessService extends Service {
    private static final String TAG = "XiaomiPartsDozeBrightness";
    private static final boolean DEBUG = false;

    private static final String DISP_FEATURE_DIR =
            "/sys/devices/virtual/mi_display/disp_feature/disp-DSI-0/";
    private static final String DOZE_BRIGHTNESS_NODE = DISP_FEATURE_DIR + "doze_brightness";
    private static final String MIPI_RW_NODE = DISP_FEATURE_DIR + "mipi_rw";
    private static final String AOD_SENSOR_TYPE = "xiaomi.sensor.aod";

    /* doze_brightness values (mi_dsi_panel_set_doze_brightness) */
    private static final int DOZE_BRIGHTNESS_HIGH = 1;
    private static final int DOZE_BRIGHTNESS_LOW = 2;

    /* AOD levels; the panel picks its idle-mode brightness from register 0x6d */
    private static final int LEVEL_LOW = 0;
    private static final int LEVEL_MID = 1;
    private static final int LEVEL_HIGH = 2;
    private static final String[] LEVEL_REG = { "02", "01", "00" };

    /* xiaomi.sensor.aod: 3 = very dark, 4 = bright, 5 = dark */
    private static final int AOD_SENSOR_VERY_DARK = 3;
    private static final int AOD_SENSOR_BRIGHT = 4;

    /* The AOD sensor only reports changes; until it does, pick the level from lux */
    private static final float LOW_LUX_THRESHOLD = 2.0f;
    private static final float HIGH_LUX_THRESHOLD = 50.0f;

    /* Let the panel finish its doze transition before overriding the level */
    private static final long APPLY_DELAY_MS = 500;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DisplayManager mDisplayManager;
    private SensorManager mSensorManager;
    private Sensor mAodSensor;
    private Sensor mLightSensor;
    private boolean mDozing;
    private boolean mSensorRegistered;
    private boolean mLightSensorRegistered;
    private int mLevel = -1;

    private final Runnable mApplyRunnable = this::applyLevel;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                updateDozeState();
            }
        }
    };

    private final SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            final int value = (int) event.values[0];
            mLevel = value == AOD_SENSOR_BRIGHT ? LEVEL_HIGH
                    : value == AOD_SENSOR_VERY_DARK ? LEVEL_LOW : LEVEL_MID;
            if (DEBUG) Log.d(TAG, "aod sensor: " + value + " -> level " + mLevel);
            applyLevel();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            setLightSensorEnabled(false);
            if (mLevel >= 0) return;
            final float lux = event.values[0];
            mLevel = lux >= HIGH_LUX_THRESHOLD ? LEVEL_HIGH
                    : lux < LOW_LUX_THRESHOLD ? LEVEL_LOW : LEVEL_MID;
            if (DEBUG) Log.d(TAG, "initial lux " + lux + " -> level " + mLevel);
            applyLevel();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mDisplayManager = getSystemService(DisplayManager.class);
        mSensorManager = getSystemService(SensorManager.class);
        for (Sensor sensor : mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (AOD_SENSOR_TYPE.equals(sensor.getStringType())) {
                mAodSensor = sensor;
                break;
            }
        }
        if (mAodSensor == null) {
            Log.e(TAG, "No " + AOD_SENSOR_TYPE + " sensor, stopping");
            stopSelf();
            return;
        }
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        updateDozeState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        setSensorEnabled(false);
        setLightSensorEnabled(false);
        mHandler.removeCallbacks(mApplyRunnable);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateDozeState() {
        final Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        final int state = display != null ? display.getState() : Display.STATE_UNKNOWN;
        mDozing = state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND;
        if (DEBUG) Log.d(TAG, "display state " + state + ", dozing=" + mDozing);

        setSensorEnabled(mDozing);
        setLightSensorEnabled(mDozing && mLevel < 0);
        mHandler.removeCallbacks(mApplyRunnable);
        if (mDozing) {
            mHandler.postDelayed(mApplyRunnable, APPLY_DELAY_MS);
        }
    }

    private void setSensorEnabled(boolean enabled) {
        if (enabled == mSensorRegistered) return;
        if (enabled) {
            mSensorManager.registerListener(mSensorListener, mAodSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        } else {
            mSensorManager.unregisterListener(mSensorListener);
        }
        mSensorRegistered = enabled;
    }

    private void setLightSensorEnabled(boolean enabled) {
        if (mLightSensor == null || enabled == mLightSensorRegistered) return;
        if (enabled) {
            mSensorManager.registerListener(mLightListener, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        } else {
            mSensorManager.unregisterListener(mLightListener);
        }
        mLightSensorRegistered = enabled;
    }

    private void applyLevel() {
        if (!mDozing || mLevel < 0) return;
        // Let the kernel enter its doze mode, then set the exact panel level: the kernel only
        // knows high/low and skips writes it thinks are already applied (e.g. mid -> low).
        writeNode(DOZE_BRIGHTNESS_NODE, String.valueOf(
                mLevel == LEVEL_HIGH ? DOZE_BRIGHTNESS_HIGH : DOZE_BRIGHTNESS_LOW));
        writeNode(MIPI_RW_NODE, "00 00 00 39 00 00 40 00 00 03 f0 aa 1b");
        writeNode(MIPI_RW_NODE, "00 00 00 15 00 00 40 00 00 02 6d " + LEVEL_REG[mLevel]);
        writeNode(MIPI_RW_NODE, "00 00 00 39 00 00 40 00 00 03 f0 aa 10");
        writeNode(MIPI_RW_NODE, "00 00 00 15 00 00 00 00 00 02 cf 09");
    }

    private static void writeNode(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + path, e);
        }
    }
}
