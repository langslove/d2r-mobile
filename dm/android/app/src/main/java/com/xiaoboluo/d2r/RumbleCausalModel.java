package com.xiaoboluo.d2r;

import java.util.Arrays;

/** Causal envelope model shared by standard and experimental output. Primitive candidate intents only, no output device. */
final class RumbleCausalModel {
    static final long NS=1_000_000L;
    static final long[] HANDLES={3,0x10003,0x2004,0x12004};
    static final int IDLE=0, ACTIVE=1, ZERO_PENDING=2, ENDED=3, TIMEOUT=4, INVALID=5;
    static final int BASELINE=0, PROBING=1, CONFIRMED=2, FALLBACK=3;
    static final String[] STATES={"IDLE","ACTIVE","ZERO_PENDING","ENDED","TIMEOUT","INVALID"};
    static final String[] PHASES={"BASELINE","PROBING","CONFIRMED","FALLBACK"};

    static int amplitude(double a,double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b) || a<0 || b<0 || a>1 || b>1) return 0;
        return Math.min(128,(int)Math.floor(64*Math.max(a,b)+.5));
    }
    static double value(long word) { return Float.intBitsToFloat((int)word); }
    static boolean valid(long[] raw,int at) {
        for (int k=0;k<4;k++) {
            double v=value(raw[at+k]);
            if ((raw[at+k]&~0xffffffffL)!=0 || !Double.isFinite(v) || v<0 || (k%2==0 && v>1)) return false;
        }
        return true;
    }
    static int ttl(long now,long updated) {
        if (updated<=0 || now<updated) return 0;
        return (int)Math.max(0,(150*NS-(now-updated))/NS);
    }
    static final class Channel {
        long seq,updated,lastPositive,zeroSince=-1,event,previousTime;
        int status=IDLE,phase=BASELINE,streak,samples,confirmations,fallbacks,evictions;
        long firstConfirmed=-1,onset,firstRequestMs=-1,stopMs=-1;
        String reason="BASELINE";
        final long[] lastRaw=new long[4];
        final double[] reference=new double[4], previous=new double[4], lastValue=new double[4], v=new double[4];
        boolean hasPrevious,hasSeq;
        final long[][] cacheRaw=new long[128][4];
        final long[] cacheTime=new long[128],cacheEvent=new long[128];
        final int[] cacheAmp=new int[128];
        int cacheCount,cacheHead;
        void fallback(String why) {
            if (phase!=FALLBACK) { fallbacks++;phase=FALLBACK;reason=why; }
        }
        void advance(long now) {
            if (updated!=0 && now-updated>=150*NS && status!=IDLE && status!=ENDED && status!=TIMEOUT && status!=INVALID) {
                status=TIMEOUT;reason="TIMEOUT";
            } else if (zeroSince>=0 && now-zeroSince>=60*NS && status==ZERO_PENDING) {
                status=ENDED;reason="QUIET_END";
            }
        }
        void feed(long time,long sequence,long[] raw,int at,boolean readable) {
            advance(time);
            if (time<updated || (hasSeq && sequence<seq)) { status=INVALID;fallback("OUT_OF_ORDER");return; }
            if (hasSeq && seq==sequence) {
                boolean same=time==updated;
                for(int k=0;k<4;k++) same &= lastRaw[k]==raw[at+k];
                if (same) return;
                status=INVALID;fallback("CONFLICTING_DUPLICATE");return;
            }
            boolean gap=hasSeq && sequence!=seq+1;
            hasSeq=true;seq=sequence;updated=time;
            System.arraycopy(raw,at,lastRaw,0,4);
            if (!readable || !valid(raw,at)) { status=INVALID;fallback("INVALID_VALUE");return; }
            for(int k=0;k<4;k++) v[k]=value(raw[at+k]);
            if (Math.max(v[0],v[2])==0) {
                if (gap && status!=IDLE && status!=ENDED && status!=TIMEOUT && status!=INVALID) fallback("SEQUENCE_GAP");
                if (status!=IDLE && status!=ENDED && status!=TIMEOUT && status!=INVALID) {
                    if (zeroSince<0) zeroSince=time;
                    status=ZERO_PENDING;
                }
                return;
            }
            boolean fresh=status==IDLE || status==ENDED || status==TIMEOUT || status==INVALID;
            if (fresh) {
                event++;onset=time;status=ACTIVE;phase=BASELINE;reason="NEW_ENVELOPE";
                System.arraycopy(v,0,reference,0,4);hasPrevious=false;previousTime=0;
                streak=0;samples=0;firstConfirmed=-1;firstRequestMs=-1;stopMs=-1;
            }
            status=ACTIVE;zeroSince=-1;lastPositive=time;
            if (gap && !fresh) fallback("SEQUENCE_GAP");
            if (v[0]<=0 || v[2]<=0 || v[1]<=1e-5 || v[3]<=1e-5) fallback("INSUFFICIENT_BANDS");
            if (phase!=FALLBACK && hasPrevious) {
                long dt=time-previousTime;
                if (dt>40*NS) fallback("SAMPLE_GAP");
                else if (Math.abs(v[0]-reference[0])>1e-5 || Math.abs(v[2]-reference[2])>1e-5) fallback("AMPLITUDE_STRUCTURE_CHANGED");
                else if (v[1]>lastValue[1]+Math.max(1e-5,lastValue[1]*1e-5)
                        || v[3]>lastValue[3]+Math.max(1e-5,lastValue[3]*1e-5)) fallback("NON_MONOTONIC");
                else if (Math.abs(v[1]/reference[1]-v[3]/reference[3])>.05) fallback("BAND_SHAPE_MISMATCH");
                else if (dt>=5*NS) {
                    boolean low=v[1]<previous[1]*.99,high=v[3]<previous[3]*.99;
                    if (low && high) { streak++;if(phase!=CONFIRMED) phase=PROBING; }
                    else if(low || high) fallback("UNPAIRED_FALL");
                }
            }
            if (phase!=FALLBACK) {
                reference[1]=Math.max(reference[1],v[1]);reference[3]=Math.max(reference[3],v[3]);samples++;
                double q=Math.max(v[1]/reference[1],v[3]/reference[3]);
                if(streak>=2 && q<=.95 && samples>=3 && phase!=CONFIRMED) {
                    phase=CONFIRMED;firstConfirmed=time;confirmations++;reason="CONFIRMED";
                }
            }
            int mapped=amplitude(v[0],v[2]);
            if (phase==CONFIRMED) {
                double q=Math.max(0,Math.min(1,Math.max(v[0]*v[1]/reference[1],v[2]*v[3]/reference[3])));
                mapped=(int)Math.floor(64*q+.5);
                if(q>0) mapped=Math.max(1,mapped);
            }
            int slot=find(time,raw,at);
            if(slot<0) {
                slot=(cacheHead+cacheCount)%128;
                if(cacheCount==128) { cacheHead=(cacheHead+1)%128;evictions++; }
                else cacheCount++;
            }
            cacheTime[slot]=time;cacheEvent[slot]=event;cacheAmp[slot]=mapped;
            System.arraycopy(raw,at,cacheRaw[slot],0,4);
            if(!hasPrevious || time-previousTime>=5*NS) {
                System.arraycopy(v,0,previous,0,4);previousTime=time;hasPrevious=true;
            }
            System.arraycopy(v,0,lastValue,0,4);
        }
        int find(long time,long[] raw,int at) {
            for(int i=0;i<cacheCount;i++) {
                int slot=(cacheHead+i)%128;
                if(cacheTime[slot]!=time) continue;
                boolean same=true;
                for(int k=0;k<4;k++) same &= cacheRaw[slot][k]==raw[at+k];
                if(same) return slot;
            }
            return -1;
        }
        int map(long time,long[] raw,int at) {
            int baseline=amplitude(value(raw[at]),value(raw[at+2]));
            if(Math.max(value(raw[at]),value(raw[at+2]))==0) return 0;
            if(phase!=CONFIRMED) return baseline;
            int slot=find(time,raw,at);
            if(slot<0 || cacheEvent[slot]!=event) { fallback("MISSING_HISTORY");return baseline; }
            return cacheAmp[slot];
        }
    }
    static final class Legacy {
        final int[] held=new int[4], choices=new int[8];
        final long[] until=new long[4];
        void reset() { Arrays.fill(held,0);Arrays.fill(until,0); }
        void choices(long now,long[] raw) {
            if(raw.length!=60 || raw[0]!=1 || raw[1]<0 || (raw[3]&~15L)!=0) {
                reset();Arrays.fill(choices,0);return;
            }
            for(int i=0;i<4;i++) {
                int b=4+i*14;
                if((raw[3]&(1L<<i))==0 || raw[b+1]==0 || raw[b]!=HANDLES[i] || raw[b+3]!=1 || !valid(raw,b+4)) {
                    held[i]=0;until[i]=0;choices[2*i]=0;choices[2*i+1]=0;continue;
                }
                int life=ttl(raw[1],raw[b+2]);
                int current=life>0?amplitude(value(raw[b+4]),value(raw[b+6])):0;
                int peakLife=ttl(raw[1],raw[b+9]);
                int peak=raw[b+8]>0 && peakLife>0 && valid(raw,b+10)?amplitude(value(raw[b+10]),value(raw[b+12])):0;
                if(peak>current && (now>=until[i] || peak>=held[i])) { held[i]=peak;until[i]=now+Math.min(40,peakLife); }
                int value=current;
                if(until[i]>now && held[i]>=value) {
                    life=held[i]>value?(int)(until[i]-now):Math.max(life,(int)(until[i]-now));value=held[i];
                }
                choices[2*i]=value;choices[2*i+1]=life;
            }
        }
    }
    static final class Clock {
        boolean active;
        int last,op,amplitude,duration,target;
        long started,effectUntil,firstRequest=-1,lastStop=-1;
        int requests,cancels;
        String reason="ZERO";
        void stop(long now) {
            op=0;amplitude=0;duration=0;target=0;reason="ZERO";
            if(active) { op=2;active=false;last=0;cancels++;lastStop=now; }
        }
        boolean tick(long now,int[] choices) {
            int life=0;target=0;op=0;amplitude=0;duration=0;
            for(int i=0;i<4;i++) {
                int value=choices[2*i],remaining=choices[2*i+1];
                if(value>target || (value==target && remaining>life)) { target=value;life=remaining; }
            }
            if(target==0 || life<=0) { stop(now);return false; }
            if(active && now-started<20) { reason="THROTTLE";return true; }
            if(!active || target!=last || now-started>=40 || now>=effectUntil) {
                duration=Math.min(60,life);amplitude=target;op=1;active=true;last=target;
                started=now;effectUntil=now+duration;requests++;if(firstRequest<0) firstRequest=now;reason="VIBRATE";
            } else reason="SAME_SKIP";
            return true;
        }
    }
    final Channel[] channels={new Channel(),new Channel(),new Channel(),new Channel()};
    final Legacy baseline=new Legacy(),proxyLegacy=new Legacy();
    final Clock d1=new Clock(),candidate=new Clock();
    final int[] choices=new int[8];
    final int[] bridgeAmp=new int[4];
    final long[] bridgeUntil=new long[4],bridgeUpdated=new long[4],sequences=new long[4];
    final long[] transformed=new long[60];
    void feed(long time,long handle,long[] raw,int at,boolean readable) {
        for(int i=0;i<4;i++) if(HANDLES[i]==handle) { channels[i].feed(time,++sequences[i],raw,at,readable);return; }
    }
    void poll(long nowNs,long nowMs,long[] raw) {
        baseline.choices(nowMs,raw);
        if(!d1.tick(nowMs,baseline.choices)) baseline.reset();
        System.arraycopy(raw,0,transformed,0,60);
        for(int i=0;i<4;i++) {
            Channel c=channels[i];int b=4+i*14;c.advance(nowNs);
            for(int pass=0;pass<2;pass++) {
                int at=b+(pass==0?4:10),time=b+(pass==0?2:9);
                if(valid(raw,at)) {
                    int mapped=c.map(raw[time],raw,at);
                    transformed[at]=transformed[at+2]=Float.floatToRawIntBits(mapped/64f)&0xffffffffL;
                }
            }
        }
        proxyLegacy.choices(nowMs,transformed);
        for(int i=0;i<4;i++) {
            int b=4+i*14;Channel c=channels[i];int amp=baseline.choices[2*i],life=baseline.choices[2*i+1];
            if((raw[3]&(1L<<i))==0 || raw[b+1]==0 || raw[b]!=HANDLES[i] || raw[b+3]!=1 || !valid(raw,b+4)
                    || c.status==INVALID || c.status==TIMEOUT) {
                amp=0;life=0;bridgeAmp[i]=0;bridgeUntil[i]=0;bridgeUpdated[i]=0;
            } else if(c.phase==CONFIRMED) {
                if(c.status==ENDED) { amp=0;life=0;bridgeAmp[i]=0;bridgeUntil[i]=0;bridgeUpdated[i]=0; }
                else {
                    life=ttl(raw[1],raw[b+2]);int peakLife=ttl(raw[1],raw[b+9]);
                    int current=Math.max(value(raw[b+4]),value(raw[b+6]))>0 && life>0?c.map(raw[b+2],raw,b+4):0;
                    if(current>0) { amp=current;bridgeAmp[i]=0;bridgeUntil[i]=0;bridgeUpdated[i]=0; }
                    else if(raw[b+8]>0 && peakLife>0 && valid(raw,b+10)) {
                        amp=c.map(raw[b+9],raw,b+10);bridgeAmp[i]=amp;bridgeUntil[i]=nowMs+Math.min(20,peakLife);
                        bridgeUpdated[i]=raw[b+9];life=Math.min(40,peakLife);
                    } else {
                        int remaining=ttl(nowNs,bridgeUpdated[i]);
                        amp=bridgeUntil[i]>nowMs && remaining>0?bridgeAmp[i]:0;
                        life=amp>0?Math.min(40,remaining):0;
                    }
                }
            }
            choices[2*i]=amp;choices[2*i+1]=life;
        }
        if(!candidate.tick(nowMs,choices)) {
            proxyLegacy.reset();Arrays.fill(bridgeAmp,0);Arrays.fill(bridgeUntil,0);Arrays.fill(bridgeUpdated,0);
        }
        for(int i=0;i<4;i++) {
            Channel c=channels[i];
            if(candidate.op==1 && choices[2*i]>0 && c.firstRequestMs<0) c.firstRequestMs=nowMs;
            if(candidate.op==2 && c.zeroSince>=0) c.stopMs=nowMs;
        }
    }
}
