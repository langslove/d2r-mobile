package com.xiaoboluo.d2r;

/** Native raw-v1: four header longs, four 14-long channel rows. Float bits are unsigned words. */
final class RumbleDRaw {
    static final int WORDS=60, HEADER=4, ROW=14;
    static float value(long[] raw,int base,int field) { return Float.intBitsToFloat((int)raw[base+field]); }
    static boolean valid(long[] raw,int base,int offset) {
        for (int k=0;k<4;k++) {
            long word=raw[base+offset+k]; if ((word & ~0xffffffffL)!=0) return false;
            float f=Float.intBitsToFloat((int)word);
            if (!Float.isFinite(f) || f<0 || (k%2==0 && f>1)) return false;
        }
        return true;
    }
    static int ttl(long now,long updated) {
        if (updated<=0 || now<updated || now-updated>=150_000_000L) return 0;
        return (int)((150_000_000L-(now-updated))/1_000_000L);
    }
}
