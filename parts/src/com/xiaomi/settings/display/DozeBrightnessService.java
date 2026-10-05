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
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Drives the panel's own AOD brightness while dozing. In doze the panel ignores the regular
 * backlight and only honours its idle-mode level, which the kernel resets to "normal" on every
 * display power transition. The panel has three idle-mode levels (register 0x6d), the kernel only
 * exposes two through doze_brightness, so the level is also set directly.
 *
 * Each doze session starts at the last level right away. While dozing, the wake-up light sensor
 * is batched by the sensor hub so lux is followed at low power: readings are averaged over a short
 * window and a new level is only applied once it has held for a few seconds. The panel is only
 * rewritten when the level actually changes.
 */
public class DozeBrightnessService extends Service {
    private static final String TAG = "XiaomiPartsDozeBrightness";
    private static final boolean DEBUG = false;

    private static final String DISP_FEATURE_DIR =
            "/sys/devices/virtual/mi_display/disp_feature/disp-DSI-0/";
    private static final String DOZE_BRIGHTNESS_NODE = DISP_FEATURE_DIR + "doze_brightness";
    private static final String MIPI_RW_NODE = DISP_FEATURE_DIR + "mipi_rw";

    /* doze_brightness values (mi_dsi_panel_set_doze_brightness) */
    private static final int DOZE_BRIGHTNESS_HIGH = 1;
    private static final int DOZE_BRIGHTNESS_LOW = 2;

    /* AOD levels; the panel picks its idle-mode brightness from register 0x6d */
    private static final int LEVEL_UNKNOWN = -1;
    private static final int LEVEL_LOW = 0;
    private static final int LEVEL_MID = 1;
    private static final int LEVEL_HIGH = 2;
    private static final String[] LEVEL_REG = { "02", "01", "00" };

    /* Lux thresholds with hysteresis. Mid vs high is only visible in a dim room, so mid covers
     * dim rooms and high everything brighter */
    private static final float LOW_TO_MID_LUX = 5.0f;
    private static final float MID_TO_LOW_LUX = 3.0f;
    private static final float MID_TO_HIGH_LUX = 50.0f;
    private static final float HIGH_TO_MID_LUX = 35.0f;

    /* Let the panel finish its doze transition before setting the level */
    private static final long APPLY_DELAY_MS = 500;

    /* Light sensor batching: sample every second, wake the CPU at most every 5 s */
    private static final int LIGHT_SAMPLING_US = 1000000;
    private static final int LIGHT_MAX_LATENCY_US = 5000000;

    /* Lux is averaged over this window */
    private static final long AVERAGE_WINDOW_MS = 4000;

    /* A new level has to hold this long before it is applied */
    private static final long STABLE_MS = 3000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DisplayManager mDisplayManager;
    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private PowerManager.WakeLock mWakeLock;
    private boolean mDozing;
    private boolean mLightSensorRegistered;
    private int mDisplayState = Display.STATE_UNKNOWN;
    private int mLevel = LEVEL_UNKNOWN;
    private int mAppliedLevel = LEVEL_UNKNOWN;
    private int mPendingLevel = LEVEL_UNKNOWN;

    /* {event time ms, lux} readings inside the averaging window */
    private final ArrayDeque<float[]> mReadings = new ArrayDeque<>();

    private final Runnable mApplyRunnable = this::applyLevel;
    private final Runnable mConfirmRunnable = this::confirmPendingLevel;

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

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            onLux(event.timestamp / 1000000L, event.values[0]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mDisplayManager = getSystemService(DisplayManager.class);
        mSensorManager = getSystemService(SensorManager.class);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT, true /* wakeUp */);
        if (mLightSensor == null) {
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
        if (mLightSensor == null) {
            Log.e(TAG, "No light sensor, stopping");
            stopSelf();
            return;
        }
        mWakeLock = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(false);
        mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
        updateDozeState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mLightSensor != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
            stopDozeSession();
        }
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
            stopDozeSession();
            return;
        }
        if (!wasDozing) {
            // New doze session: keep the last level, the light sensor corrects it
            mReadings.clear();
            setLightSensorEnabled(true);
        }
        mHandler.postDelayed(mApplyRunnable, APPLY_DELAY_MS);
    }

    private void stopDozeSession() {
        mHandler.removeCallbacks(mApplyRunnable);
        mHandler.removeCallbacks(mConfirmRunnable);
        mPendingLevel = LEVEL_UNKNOWN;
        setLightSensorEnabled(false);
        mWakeLock.release();
    }

    private void setLightSensorEnabled(boolean enabled) {
        if (enabled == mLightSensorRegistered) return;
        if (enabled) {
            mSensorManager.registerListener(mLightListener, mLightSensor,
                    LIGHT_SAMPLING_US, LIGHT_MAX_LATENCY_US, mHandler);
        } else {
            mSensorManager.unregisterListener(mLightListener);
        }
        mLightSensorRegistered = enabled;
    }

    private void onLux(long timeMs, float lux) {
        if (!mDozing) return;
        mReadings.addLast(new float[] { timeMs, lux });
        while (timeMs - (long) mReadings.peekFirst()[0] > AVERAGE_WINDOW_MS) {
            mReadings.removeFirst();
        }
        final float avg = averageLux();
        final int candidate = levelForLux(avg, mLevel);
        if (DEBUG) Log.d(TAG, "lux " + lux + " avg " + avg + " -> " + candidate);

        if (mLevel == LEVEL_UNKNOWN) {
            // First reading ever, nothing to keep stable
            setLevel(candidate);
        } else if (candidate == mLevel) {
            cancelPendingLevel();
        } else if (candidate != mPendingLevel) {
            // Only apply the new level if it still holds after STABLE_MS
            mPendingLevel = candidate;
            mWakeLock.acquire(STABLE_MS + 1000);
            mHandler.removeCallbacks(mConfirmRunnable);
            mHandler.postDelayed(mConfirmRunnable, STABLE_MS);
        }
    }

    private float averageLux() {
        if (mReadings.isEmpty()) return 0;
        float sum = 0;
        for (float[] reading : mReadings) {
            sum += reading[1];
        }
        return sum / mReadings.size();
    }

    private void confirmPendingLevel() {
        if (mDozing && mPendingLevel != LEVEL_UNKNOWN
                && levelForLux(averageLux(), mLevel) == mPendingLevel) {
            setLevel(mPendingLevel);
        }
        cancelPendingLevel();
    }

    private void cancelPendingLevel() {
        mPendingLevel = LEVEL_UNKNOWN;
        mHandler.removeCallbacks(mConfirmRunnable);
        mWakeLock.release();
    }

    private void setLevel(int level) {
        mLevel = level;
        // While the panel is still settling, the pending apply picks the level up
        if (!mHandler.hasCallbacks(mApplyRunnable)) {
            applyLevel();
        }
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
