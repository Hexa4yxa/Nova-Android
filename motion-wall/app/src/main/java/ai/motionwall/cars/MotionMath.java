package ai.motionwall.cars;

/** Pure deterministic framing and loop functions, easy to regression-test. */
public final class MotionMath {
    private MotionMath() { }
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
    public static float fitScale(int photoWidth, int photoHeight, int canvasWidth, int canvasHeight) {
        if (photoWidth <= 0 || photoHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return 0f;
        // Unlike the old cover crop, keep the complete car photo visible.
        return Math.min(canvasWidth * .94f / photoWidth, canvasHeight * .76f / photoHeight);
    }
    public static float coverScale(int photoWidth, int photoHeight, int canvasWidth, int canvasHeight) {
        if (photoWidth <= 0 || photoHeight <= 0 || canvasWidth <= 0 || canvasHeight <= 0) return 0f;
        return Math.max(canvasWidth / (float) photoWidth, canvasHeight / (float) photoHeight);
    }
    public static float wave(float phase) {
        return (float) Math.sin(2d * Math.PI * phase);
    }
    public static float softPulse(float phase) {
        return .5f - .5f * (float) Math.cos(2d * Math.PI * phase);
    }
    /** 
     * Target GPU 30 fps, software fallback 15 fps, power saver 10 fps.
     * Subtract actual draw time so a slow frame does not add a second,
     * unnecessary delay. Keep some idle time to avoid starving launcher UI.
     */
    public static long renderDelay(long elapsedMs, boolean powerSave, boolean software) {
        long target = powerSave ? 100L : software ? 67L : 33L;
        return Math.max(8L, target - Math.max(0L, elapsedMs));
    }
    public static long frameDelay(boolean powerSave) { return powerSave ? 100L : 33L; }
}
