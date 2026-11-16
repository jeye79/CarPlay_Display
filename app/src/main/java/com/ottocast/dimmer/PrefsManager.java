package com.ottocast.dimmer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists user preferences (brightness, blue-light filter strength, clock format, capsule position).
 */
public final class PrefsManager {
    private static final String PREFS_NAME = "ottocast_dimmer_prefs";

    private static final String KEY_BRIGHTNESS = "brightness";
    private static final String KEY_BLUE_LIGHT = "blue_light";
    private static final String KEY_USE_24_HOUR = "use_24_hour";
    private static final String KEY_CAPSULE_X = "capsule_x";
    private static final String KEY_CAPSULE_Y = "capsule_y";

    private static final int DEFAULT_BRIGHTNESS = 70;
    private static final int DEFAULT_BLUE_LIGHT = 20;
    private static final boolean DEFAULT_USE_24_HOUR = true;

    private final SharedPreferences prefs;

    public PrefsManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int getBrightness() {
        return clampPercent(prefs.getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS));
    }

    public void setBrightness(int brightness) {
        prefs.edit().putInt(KEY_BRIGHTNESS, clampPercent(brightness)).apply();
    }

    public int getBlueLight() {
        return clampPercent(prefs.getInt(KEY_BLUE_LIGHT, DEFAULT_BLUE_LIGHT));
    }

    public void setBlueLight(int blueLight) {
        prefs.edit().putInt(KEY_BLUE_LIGHT, clampPercent(blueLight)).apply();
    }

    public boolean isUse24Hour() {
        return prefs.getBoolean(KEY_USE_24_HOUR, DEFAULT_USE_24_HOUR);
    }

    public void setUse24Hour(boolean use24Hour) {
        prefs.edit().putBoolean(KEY_USE_24_HOUR, use24Hour).apply();
    }

    public int getCapsuleX() {
        return prefs.getInt(KEY_CAPSULE_X, 0);
    }

    public int getCapsuleY(int defaultY) {
        return prefs.getInt(KEY_CAPSULE_Y, defaultY);
    }

    public void setCapsulePosition(int x, int y) {
        prefs.edit()
                .putInt(KEY_CAPSULE_X, x)
                .putInt(KEY_CAPSULE_Y, y)
                .apply();
    }

    public static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
