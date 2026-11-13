package com.xiaoboluo.d2r;

/** Worker-confined D1 policy. Same bounded timing as C; independent raw input and mapping. */
final class RumbleDOutputCore {
    interface Observer { void decision(long now,int amplitude,int duration,String reason,long[] raw); }
    private Observer observer;
    private final RumbleDCalibration calibration;
    private final int[] held=new int[4];
    private final long[] until=new long[4];
    private int last;
    private long lastStart,effectUntil,lastNow;
    private boolean active;
    RumbleDOutputCore(RumbleDCalibration calibration) { this.calibration=calibration; }
    void observer(Observer observer) { this.observer=observer; }
    private void observe(long now,int amplitude,int duration,String reason,long[] raw) {
        if (observer!=null) try { observer.decision(now,amplitude,duration,reason,raw); } catch (RuntimeException ignored) { }
    }
    void tick(long now,long[] raw,RumbleOutputCore.Motor motor) {
        if (raw==null || raw.length!=60 || raw[0]!=1 || raw[1]<0 || now<lastNow || (raw[3]&~15L)!=0) {
            observe(now,0,0,"INVALID",raw);stop(motor);return;
        }
        lastNow=now;
        int target=0,remaining=0;
        for (int i=0;i<4;i++) {
            int b=4+i*14;
            long expected=i<2 ? 3L+((long)i<<16) : 0x2004L+((long)(i-2)<<16);
            if ((raw[3]&(1L<<i))==0 || raw[b+1]==0 || raw[b]!=expected || raw[b+3]!=1
                    || !RumbleDRaw.valid(raw,b,4)) { held[i]=0;until[i]=0;continue; }
            int ttl=RumbleDRaw.ttl(raw[1],raw[b+2]);
            int current=ttl>0?calibration.map(RumbleDRaw.value(raw,b,4),RumbleDRaw.value(raw,b,6)):0;
            int peakTtl=RumbleDRaw.ttl(raw[1],raw[b+9]);
            int peak=raw[b+8]>0 && peakTtl>0 && RumbleDRaw.valid(raw,b,10)
                    ?calibration.map(RumbleDRaw.value(raw,b,10),RumbleDRaw.value(raw,b,12)):0;
            if (peak>current && (now>=until[i] || peak>=held[i])) {
                held[i]=peak;until[i]=now+Math.min(40,peakTtl);
            }
            int value=current,life=ttl;
            if (until[i]>now && held[i]>=value) {
                life=held[i]>value?(int)(until[i]-now):Math.max(life,(int)(until[i]-now));value=held[i];
            }
            if (value>target || (value==target && life>remaining)) { target=value;remaining=life; }
        }
        if (target==0 || remaining<=0) { observe(now,0,0,"ZERO_OR_EXPIRED",raw);stop(motor);return; }
        if (active && now-lastStart<20) { observe(now,target,0,"THROTTLE",raw);return; }
        if (!active || target!=last || now-lastStart>=40 || now>=effectUntil) {
            int duration=Math.min(60,remaining);observe(now,target,duration,"VIBRATE",raw);
            motor.vibrate(duration,target);active=true;last=target;lastStart=now;effectUntil=now+duration;
        } else observe(now,target,0,"SAME_SKIP",raw);
    }
    void stop(RumbleOutputCore.Motor motor) {
        java.util.Arrays.fill(held,0);java.util.Arrays.fill(until,0);
        if (active) { motor.cancel();active=false;last=0; }
    }
    void fail(RumbleOutputCore.Motor motor) { motor.cancel();active=false;java.util.Arrays.fill(held,0);java.util.Arrays.fill(until,0);last=0; }
}
