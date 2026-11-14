package ai.motionwall.cars;

import android.content.Context;
import android.content.SharedPreferences;

/** Persist wallpaper settings in the same private store as the photo. */
public final class MotionSettings {
    public static final String MODE_KEY = "motion_mode_v2";
    public static final String STRENGTH_KEY = "motion_strength_v2";
    public static final String NIGHT = "night";
    public static final String RAIN = "rain";
    public static final String NEON = "neon";
    public static final String DRIVE = "drive";

    public final String mode;
    public final int strength;

    public MotionSettings(String mode, int strength) {
        this.mode = validMode(mode) ? mode : NIGHT;
        this.strength = MotionMath.clamp(strength, 20, 100);
    }
    public static boolean validMode(String mode) {
        return NIGHT.equals(mode) || RAIN.equals(mode) || NEON.equals(mode) || DRIVE.equals(mode);
    }
    public static MotionSettings read(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PhotoStore.PREFS, Context.MODE_PRIVATE);
        return new MotionSettings(prefs.getString(MODE_KEY, NIGHT), prefs.getInt(STRENGTH_KEY, 65));
    }
    public boolean save(Context context) {
        return context.getSharedPreferences(PhotoStore.PREFS, Context.MODE_PRIVATE)
            .edit().putString(MODE_KEY, mode).putInt(STRENGTH_KEY, strength).commit();
    }
}
