package com.xiaoboluo.d2r;

/** D1 amplitude only. Frequency is validated/transported, never used for strength. */
final class RumbleDCalibration {
    final int peak, floor;
    final double gamma, floorFrom;
    RumbleDCalibration() { this(64,1,0,0.05); }
    RumbleDCalibration(int peak,double gamma,int floor,double floorFrom) {
        if (peak<1 || peak>128 || !Double.isFinite(gamma) || gamma<=0 || gamma>4
                || floor<0 || floor>peak || !Double.isFinite(floorFrom) || floorFrom<=0 || floorFrom>1)
            throw new IllegalArgumentException("invalid D1 calibration");
        this.peak=peak;this.gamma=gamma;this.floor=floor;this.floorFrom=floorFrom;
    }
    int map(float low,float high) {
        if (!Float.isFinite(low) || !Float.isFinite(high) || low<0 || high<0 || low>1 || high>1) return 0;
        double u=Math.max(low,high);
        if (u==0) return 0;
        int a=(int)Math.round(peak*Math.pow(u,gamma));
        if (floor>0 && u>=floorFrom) a=Math.max(floor,a);
        return Math.min(128,a);
    }
}
