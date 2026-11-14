package ai.motionwall.cars;

/** No Android dependencies: 8-second seam-safe animation phases. */
public final class LoopMath {
    private static final float TAU = (float)(2.0*Math.PI);
    private LoopMath(){}
    public static float phase(long elapsedMs) {return Math.floorMod(elapsedMs,8000L)/8000f;}
    public static float wave(float phase) {return (float)Math.sin(TAU*phase);}
    public static float smooth(float phase) {return 0.5f-0.5f*(float)Math.cos(TAU*phase);}
    public static float wrap01(float x) {return x-(float)Math.floor(x);}
}
