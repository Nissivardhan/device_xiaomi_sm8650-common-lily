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
 * Ambient-light based AOD brightness.
 *
 * In doze the panel stays in its low power idle mode (doze_brightness) but still takes the
 * regular backlight level, so the AOD brightness can follow the room continuously. Lux is
 * averaged over a short window, sampled when the doze session starts, when the AOD sensor
 * reports a light change and about once a minute, and the brightness only moves when the
 * target changes meaningfully, with a short ramp instead of a jump.
 */
public class DozeBrightnessService extends Service {
    private static final String TAG = "XiaomiPartsDozeBrightness";
    private static final boolean DEBUG = false;

    private static final String DOZE_BRIGHTNESS_NODE =
            "/sys/devices/virtual/mi_display/disp_feature/disp-DSI-0/doze_brightness";
    private static final String BACKLIGHT_NODE =
            "/sys/class/backlight/panel0-backlight/brightness";
    private static final String AOD_SENSOR_TYPE = "xiaomi.sensor.aod";

    /* doze_brightness: 2 = low power idle mode */
    private static final String DOZE_IDLE_MODE = "2";

    /* Lux -> backlight curve (0..4095), interpolated on log(lux + 1) */
    private static final float[] CURVE_LUX = { 0f, 5f, 30f, 150f, 800f, 5000f };
    private static final int[] CURVE_LEVEL = { 6, 20, 45, 100, 220, 450 };

    /* Only move when the target differs by more than this from the current level */
    private static final float CHANGE_THRESHOLD = 0.2f;
    private static final int MIN_LEVEL_DELTA = 3;

    /* Let the panel finish its doze transition before setting the level */
    private static final long ENTER_DELAY_MS = 500;
    /* The AOD sensor reports a stale value as soon as it is enabled; skip it */
    private static final long AOD_SENSOR_SETTLE_MS = 1500;
    /* Average lux over this window */
    private static final long SAMPLE_WINDOW_MS = 2000;
    /* Re-sample periodically; only runs when the CPU is awake anyway (e.g. the AOD clock tick) */
    private static final long RESAMPLE_INTERVAL_MS = 60000;
    /* Ramp to a new level instead of jumping */
    private static final int RAMP_STEPS = 8;
    private static final long RAMP_STEP_MS = 100;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private DisplayManager mDisplayManager;
    private SensorManager mSensorManager;
    private Sensor mAodSensor;
    private Sensor mLightSensor;
    private int mDisplayState = Display.STATE_UNKNOWN;
    private boolean mDozing;
    private boolean mAodSensorRegistered;
    private boolean mSampling;
    private long mAodSensorEnabledTime;
    private float mLuxSum;
    private int mLuxCount;
    private int mLevel = -1;
    private int mRampFrom;
    private int mRampTo;
    private int mRampStep;

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
            startSampling();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            mLuxSum += event.values[0];
            mLuxCount++;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final Runnable mEnterRunnable = () -> {
        writeNode(DOZE_BRIGHTNESS_NODE, DOZE_IDLE_MODE);
        if (mLevel >= 0) {
            // Restore the session level right away, the kernel reset it on the transition
            writeNode(BACKLIGHT_NODE, String.valueOf(mLevel));
        }
        startSampling();
    };

    private final Runnable mFinishSampleRunnable = this::finishSampling;

    private final Runnable mResampleRunnable = () -> {
        if (mDozing) {
            startSampling();
        }
    };

    private final Runnable mRampRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mDozing) return;
            mRampStep++;
            final int level = mRampFrom + (mRampTo - mRampFrom) * mRampStep / RAMP_STEPS;
            writeNode(BACKLIGHT_NODE, String.valueOf(level));
            if (mRampStep < RAMP_STEPS) {
                mHandler.postDelayed(this, RAMP_STEP_MS);
            }
        }
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
        stopDozeSession();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateDozeState() {
        final Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        final int state = display != null ? display.getState() : Display.STATE_UNKNOWN;
        if (state == mDisplayState) return;
        final boolean wasDozing = mDozing;
        mDisplayState = state;
        mDozing = state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND;
        if (DEBUG) Log.d(TAG, "display state " + state + ", dozing=" + mDozing);

        if (!mDozing) {
            stopDozeSession();
            return;
        }
        if (!wasDozing) {
            // New doze session: starts at the last level, a fresh reading ramps from there
            setAodSensorEnabled(true);
        }
        // The kernel resets the panel on every power transition: re-apply once it settles
        mHandler.removeCallbacks(mEnterRunnable);
        mHandler.postDelayed(mEnterRunnable, ENTER_DELAY_MS);
    }

    private void stopDozeSession() {
        mHandler.removeCallbacks(mEnterRunnable);
        mHandler.removeCallbacks(mResampleRunnable);
        mHandler.removeCallbacks(mRampRunnable);
        mHandler.removeCallbacks(mFinishSampleRunnable);
        stopLightSensor();
        setAodSensorEnabled(false);
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

    private void startSampling() {
        if (!mDozing || mSampling) return;
        mHandler.removeCallbacks(mResampleRunnable);
        mLuxSum = 0;
        mLuxCount = 0;
        mSampling = true;
        mSensorManager.registerListener(mLightListener, mLightSensor,
                SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        mHandler.postDelayed(mFinishSampleRunnable, SAMPLE_WINDOW_MS);
    }

    private void stopLightSensor() {
        if (!mSampling) return;
        mSensorManager.unregisterListener(mLightListener);
        mSampling = false;
    }

    private void finishSampling() {
        stopLightSensor();
        if (!mDozing) return;
        if (mLuxCount > 0) {
            final float lux = mLuxSum / mLuxCount;
            final int target = levelForLux(lux);
            if (DEBUG) Log.d(TAG, "lux " + lux + " (" + mLuxCount + ") -> " + target);
            if (mLevel < 0) {
                setLevel(target, false);
            } else if (Math.abs(target - mLevel) >= MIN_LEVEL_DELTA
                    && Math.abs(target - mLevel) > mLevel * CHANGE_THRESHOLD) {
                setLevel(target, true);
            }
        }
        mHandler.postDelayed(mResampleRunnable, RESAMPLE_INTERVAL_MS);
    }

    private void setLevel(int level, boolean ramp) {
        mHandler.removeCallbacks(mRampRunnable);
        if (ramp && mLevel >= 0) {
            mRampFrom = mLevel;
            mRampTo = level;
            mRampStep = 0;
            mHandler.post(mRampRunnable);
        } else {
            writeNode(BACKLIGHT_NODE, String.valueOf(level));
        }
        mLevel = level;
    }

    private static int levelForLux(float lux) {
        final double x = Math.log1p(Math.max(0f, lux));
        if (lux <= CURVE_LUX[0]) return CURVE_LEVEL[0];
        for (int i = 1; i < CURVE_LUX.length; i++) {
            if (lux <= CURVE_LUX[i]) {
                final double x0 = Math.log1p(CURVE_LUX[i - 1]);
                final double x1 = Math.log1p(CURVE_LUX[i]);
                final double t = (x - x0) / (x1 - x0);
                return (int) Math.round(CURVE_LEVEL[i - 1]
                        + t * (CURVE_LEVEL[i] - CURVE_LEVEL[i - 1]));
            }
        }
        return CURVE_LEVEL[CURVE_LEVEL.length - 1];
    }

    private static void writeNode(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
        } catch (IOException e) {
            Log.e(TAG, "Failed to write " + path, e);
        }
    }
}
