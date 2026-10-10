package dev.minebridge.core;

public final class DepthMath {
    private DepthMath() {}
    /** Reconstruct camera-space Z by applying the inverse projection to the native depth sample. */
    public static float axial(float sample,float ndcX,float ndcY,float[] inverse,boolean zeroToOne) {
        if(!Float.isFinite(sample)||sample<0||sample>=0.999999f)return Float.NaN;
        float z=zeroToOne?sample:sample*2-1;
        float vz=inverse[2]*ndcX+inverse[6]*ndcY+inverse[10]*z+inverse[14];
        float vw=inverse[3]*ndcX+inverse[7]*ndcY+inverse[11]*z+inverse[15];
        float distance=-vz/vw;
        return Float.isFinite(distance)&&distance>0?distance:Float.NaN;
    }
}
