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
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Drives the panel's own AOD brightness while dozing. In doze the panel ignores the regular
 * backlight and only honours its idle-mode level, which the kernel resets to "normal" on every
 * display power transition. The panel has three idle-mode levels (register 0x6d), the kernel only
 * exposes two through doze_brightness, so the level is also set directly.
 *
 * The level is chosen from lux: once when the doze session starts, and again whenever the AOD
 * sensor reports a change in ambient light (it only reports changes, and its own coarse values
 * are not used). The panel is only rewritten when the level actually changes.
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
    private static final int LEVEL_UNKNOWN = -1;
    private static final int LEVEL_LOW = 0;
    private static final int LEVEL_MID = 1;
    private static final int LEVEL_HIGH = 2;
    private static final String[] LEVEL_REG = { "02", "01", "00" };

    /* Lux thresholds with hysteresis, so light around a threshold does not bounce the level */
    private static final float LOW_TO_MID_LUX = 3.0f;
    private static final float MID_TO_LOW_LUX = 1.5f;
    private static final float MID_TO_HIGH_LUX = 60.0f;
    private static final float HIGH_TO_MID_LUX = 40.0f;

    /* Let the panel finish its doze transition before setting the level */
    private static final long APPLY_DELAY_MS = 500;

    /* The AOD sensor reports a stale value as soon as it is enabled; skip it */
    private static final long AOD_SENSOR_SETTLE_MS = 1500;

    /* Wait for the light to settle after the AOD sensor reports a change */
    private static final long LIGHT_CHANGE_DELAY_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DisplayManager mDisplayManager;
    private SensorManager mSensorManager;
    private Sensor mAodSensor;
    private Sensor mLightSensor;
    private boolean mDozing;
    private boolean mAodSensorRegistered;
    private boolean mLightSensorRegistered;
    private int mDisplayState = Display.STATE_UNKNOWN;
    private int mLevel = LEVEL_UNKNOWN;
    private int mAppliedLevel = LEVEL_UNKNOWN;
    private long mAodSensorEnabledTime;

    private final Runnable mApplyRunnable = this::applyLevel;
    private final Runnable mSampleLightRunnable = () -> setLightSensorEnabled(mDozing);

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

    private final SensorEventListener mAodSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (SystemClock.uptimeMillis() - mAodSensorEnabledTime < AOD_SENSOR_SETTLE_MS) {
                return;
            }
            if (DEBUG) Log.d(TAG, "aod sensor: " + event.values[0]);
            // Ambient light changed: re-read lux once it has settled
            mHandler.removeCallbacks(mSampleLightRunnable);
            mHandler.postDelayed(mSampleLightRunnable, LIGHT_CHANGE_DELAY_MS);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            setLightSensorEnabled(false);
            final float lux = event.values[0];
            mLevel = levelForLux(lux, mLevel);
            if (DEBUG) Log.d(TAG, "lux " + lux + " -> level " + mLevel);
            // While the panel is still settling, the pending apply picks the level up
            if (!mHandler.hasCallbacks(mApplyRunnable)) {
                applyLevel();
            }
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
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT, true /* wakeUp */);
        if (mLightSensor == null) {
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
        if (mLightSensor == null) {
            Log.e(TAG, "No light sensor, stopping");
            stopSelf();
            return;
        }
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
        setAodSensorEnabled(false);
        setLightSensorEnabled(false);
        mHandler.removeCallbacks(mApplyRunnable);
        mHandler.removeCallbacks(mSampleLightRunnable);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static int levelForLux(float lux, int current) {
        switch (current) {
            case LEVEL_LOW:
                return lux > MID_TO_HIGH_LUX ? LEVEL_HIGH : lux > LOW_TO_MID_LUX ? LEVEL_MID
                        : LEVEL_LOW;
            case LEVEL_MID:
                return lux > MID_TO_HIGH_LUX ? LEVEL_HIGH : lux < MID_TO_LOW_LUX ? LEVEL_LOW
                        : LEVEL_MID;
            case LEVEL_HIGH:
                return lux < MID_TO_LOW_LUX ? LEVEL_LOW : lux < HIGH_TO_MID_LUX ? LEVEL_MID
                        : LEVEL_HIGH;
            default:
                return lux >= MID_TO_HIGH_LUX ? LEVEL_HIGH : lux < LOW_TO_MID_LUX ? LEVEL_LOW
                        : LEVEL_MID;
        }
    }

    private void updateDozeState() {
        final Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        final int state = display != null ? display.getState() : Display.STATE_UNKNOWN;
        if (state == mDisplayState) return;
        final boolean wasDozing = mDozing;
        mDisplayState = state;
        mDozing = state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND;
        if (DEBUG) Log.d(TAG, "display state " + state + ", dozing=" + mDozing);

        // The kernel resets the panel level on every power transition
        mAppliedLevel = LEVEL_UNKNOWN;
        mHandler.removeCallbacks(mApplyRunnable);
        if (!mDozing) {
            mHandler.removeCallbacks(mSampleLightRunnable);
            setAodSensorEnabled(false);
            setLightSensorEnabled(false);
            return;
        }
        if (!wasDozing) {
            // New doze session: start from a fresh lux reading
            mLevel = LEVEL_UNKNOWN;
            setAodSensorEnabled(true);
            setLightSensorEnabled(true);
        }
        mHandler.postDelayed(mApplyRunnable, APPLY_DELAY_MS);
    }

    private void setAodSensorEnabled(boolean enabled) {
        if (mAodSensor == null || enabled == mAodSensorRegistered) return;
        if (enabled) {
            mAodSensorEnabledTime = SystemClock.uptimeMillis();
            mSensorManager.registerListener(mAodSensorListener, mAodSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        } else {
            mSensorManager.unregisterListener(mAodSensorListener);
        }
        mAodSensorRegistered = enabled;
    }

    private void setLightSensorEnabled(boolean enabled) {
        if (enabled == mLightSensorRegistered) return;
        if (enabled) {
            mSensorManager.registerListener(mLightListener, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        } else {
            mSensorManager.unregisterListener(mLightListener);
        }
        mLightSensorRegistered = enabled;
    }

    private void applyLevel() {
        if (!mDozing || mLevel == LEVEL_UNKNOWN || mLevel == mAppliedLevel) return;
        // Let the kernel enter its doze mode, then set the exact panel level: the kernel only
        // knows high/low and skips writes it thinks are already applied (e.g. mid -> low).
        writeNode(DOZE_BRIGHTNESS_NODE, String.valueOf(
                mLevel == LEVEL_HIGH ? DOZE_BRIGHTNESS_HIGH : DOZE_BRIGHTNESS_LOW));
        writeNode(MIPI_RW_NODE, "00 00 00 39 00 00 40 00 00 03 f0 aa 1b");
        writeNode(MIPI_RW_NODE, "00 00 00 15 00 00 40 00 00 02 6d " + LEVEL_REG[mLevel]);
        writeNode(MIPI_RW_NODE, "00 00 00 39 00 00 40 00 00 03 f0 aa 10");
        writeNode(MIPI_RW_NODE, "00 00 00 15 00 00 00 00 00 02 cf 09");
        mAppliedLevel = mLevel;
    }

    private static void writeNode(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + path, e);
        }
    }
}
