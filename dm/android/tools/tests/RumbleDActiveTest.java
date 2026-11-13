package com.xiaoboluo.d2r;

import java.util.ArrayList;
import java.util.List;

public final class RumbleDActiveTest {
    static void check(boolean b) { if(!b)throw new AssertionError(); }
    static final class Clock implements java.util.function.LongSupplier {
        long ns=1_000_000_000L;public long getAsLong(){return ns;}
    }
    static final class Motor implements RumbleOutputCore.Motor {
        int starts,cancels,last;boolean fail;
        public void vibrate(int duration,int amplitude) {
            check(duration>0 && duration<=60 && amplitude>0 && amplitude<=128);
            starts++;last=amplitude;if(fail)throw new SecurityException("fake API failure");
        }
        public void cancel(){cancels++;}
    }
    static final class Fixture {
        final Clock clock=new Clock();final Motor motor=new Motor();
        final RumbleDOutputCore baseline=new RumbleDOutputCore(new RumbleDCalibration());
        final RumbleDActive active;
        final long[] raw=new long[60],batch=new long[1300],stamp=new long[4];
        long epoch=1,seq,delivered;final long[] channelSeq=new long[2];
        Fixture(long owner) {
            raw[0]=1;raw[2]=owner;raw[3]=3;stamp[0]=owner;
            active=new RumbleDActive(owner,clock,null);
        }
        void start(long ms) {
            clock.ns=ms*1_000_000L;raw[1]=clock.ns;
            for(int i=0;i<2;i++){int b=4+i*14;raw[b+8]=0;raw[b+9]=0;}
            java.util.Arrays.fill(batch,0);batch[0]=1;batch[1]=raw[2];batch[2]=epoch;batch[3]=1;
            batch[6]=delivered;batch[12]=4096;batch[13]=10;batch[15]=20;
        }
        void send(int side,float amp,float low,float high,long ns) {
            long handle=side==0?3:0x10003;int at=20+(int)batch[10]*10,b=4+side*14;
            long[] words={Float.floatToRawIntBits(amp)&0xffffffffL,Float.floatToRawIntBits(low)&0xffffffffL,Float.floatToRawIntBits(amp)&0xffffffffL,Float.floatToRawIntBits(high)&0xffffffffL};
            batch[at]=raw[2];batch[at+1]=epoch;batch[at+2]=++seq;batch[at+3]=ns;batch[at+4]=handle;batch[at+5]=31;
            System.arraycopy(words,0,batch,at+6,4);batch[10]++;
            raw[b]=handle;raw[b+1]=++channelSeq[side];raw[b+2]=ns;raw[b+3]=1;System.arraycopy(words,0,raw,b+4,4);
            if(amp>0){raw[b+8]=channelSeq[side];raw[b+9]=ns;System.arraycopy(words,0,raw,b+10,4);}
        }
        void tick() {
            batch[4]=seq;batch[7]=batch[10];batch[9]=clock.ns;batch[14]=batch[10]>0?batch[22]:0;
            stamp[1]=epoch;stamp[2]=seq;stamp[3]=raw[1];
            active.prepare(stamp,batch,out -> false);delivered+=batch[10];active.tick(clock.ns/1_000_000L,raw,baseline,motor);
        }
        void stop(){active.stop(motor);}
    }
    public static void main(String[] args) {
        Fixture f=new Fixture(101);f.start(1000);f.send(0,1,10,20,f.clock.ns);f.tick();
        check(f.motor.starts==1 && f.motor.last==64); // first tick, no batch waiting
        f.start(1020);f.send(0,1,8,16,f.clock.ns);f.tick();check(f.motor.starts==1);
        f.start(1040);f.send(0,1,6,12,f.clock.ns);f.tick();check(f.motor.starts==2 && f.motor.last==38);
        f.start(1060);f.send(0,0,0,0,f.clock.ns);f.tick();
        f.start(1080);f.tick();f.start(1100);f.tick();check(f.motor.cancels==1);
        int stopped=f.motor.starts;f.start(1200);f.tick();check(f.motor.starts==stopped);f.stop();
        // One handle stopping cannot stop the other current channel.
        f=new Fixture(102);f.start(1000);f.send(0,1,0,60,f.clock.ns);f.send(1,1,0,60,f.clock.ns);f.tick();
        f.start(1020);f.send(0,0,0,0,f.clock.ns);f.send(1,1,0,60,f.clock.ns);f.tick();check(f.motor.cancels==0);
        f.start(1040);f.send(1,0,0,0,f.clock.ns);f.tick();f.start(1060);f.tick();f.start(1080);f.tick();check(f.motor.cancels==1);f.stop();
        // The current clock expires old nonzero input; it cannot be replayed after stopping.
        f=new Fixture(103);f.start(1000);f.send(0,1,10,20,f.clock.ns);f.tick();
        f.start(1160);f.tick();check(f.motor.cancels==1);f.start(1180);f.tick();check(f.motor.starts==1);f.stop();
        // A producer gap falls back to the D1 amplitude in the same tick.
        f=new Fixture(104);f.start(1000);f.send(0,1,10,20,f.clock.ns);f.tick();
        f.start(1020);f.seq++;f.send(0,1,8,16,f.clock.ns);f.tick();check(f.motor.last==64 && f.active.summary().contains("fallback_d1=true"));f.stop();
        // A new epoch discards candidate history. Pause/restore begins at the baseline.
        f=new Fixture(105);f.start(1000);f.send(0,1,10,20,f.clock.ns);f.tick();f.stop();f.active.boundary();
        f.epoch++;f.seq=0;f.delivered=0;java.util.Arrays.fill(f.channelSeq,0);java.util.Arrays.fill(f.raw,4,60,0);
        f.start(1020);f.tick();check(f.motor.starts==1);f.start(1040);f.send(0,1,6,12,f.clock.ns);f.tick();check(f.motor.last==64);f.stop();
        // Ownership transfer serializes cancellation and only the new owner may act.
        Fixture old=new Fixture(106);old.start(1000);old.send(0,1,10,20,old.clock.ns);old.tick();
        Fixture next=new Fixture(107);old.active.fail(old.motor);check(old.motor.cancels==0);
        next.start(1020);next.send(0,1,10,20,next.clock.ns);next.tick();check(old.motor.cancels==1 && next.motor.starts==1);
        old.active.stop(old.motor);check(next.motor.cancels==0);next.stop();
        // API failure is owned and cancelled once by the session safety path.
        f=new Fixture(108);f.motor.fail=true;f.start(1000);f.send(0,1,10,20,f.clock.ns);
        try{f.tick();throw new AssertionError();}catch(SecurityException expected){f.active.fail(f.motor);}
        check(f.motor.starts==1 && f.motor.cancels==1);
        // An expired snapshot is refreshed once before any physical start.
        Clock clock=new Clock();Motor motor=new Motor();long[] oldRaw=new long[60];oldRaw[0]=1;oldRaw[1]=clock.ns-50_000_000L;oldRaw[2]=109;oldRaw[3]=3;
        RumbleDActive stale=new RumbleDActive(109,clock,(raw,stamp)->{java.util.Arrays.fill(raw,0);raw[0]=1;raw[1]=clock.ns;raw[2]=109;raw[3]=3;stamp[0]=109;stamp[1]=1;stamp[2]=0;stamp[3]=clock.ns;return true;});
        stale.tick(1000,oldRaw,new RumbleDOutputCore(new RumbleDCalibration()),motor);check(motor.starts==0 && stale.expired==1);
        // A future suffix in one batch stays queued until its own snapshot watermark.
        Fixture future=new Fixture(110);future.start(1000);future.send(0,1,10,20,future.clock.ns);
        long[] prefix=future.raw.clone();future.send(0,0,0,0,1_020_000_000L);System.arraycopy(prefix,0,future.raw,0,60);
        future.batch[4]=2;future.batch[7]=2;future.batch[14]=1;future.stamp[1]=1;future.stamp[2]=1;future.stamp[3]=future.raw[1];
        future.active.prepare(future.stamp,future.batch,out -> false);future.delivered=2;
        future.active.tick(1000,future.raw,future.baseline,future.motor);check(future.motor.starts==1 && future.motor.last==64);
        future.start(1020);future.raw[5]=2;future.raw[6]=1_020_000_000L;future.raw[8]=0;future.raw[9]=0;future.raw[10]=0;future.raw[11]=0;
        future.tick();check(future.motor.cancels==1);future.stop();
        // Fresh micro-pulses are protected once, then cannot restart from an old peak.
        Fixture pulse=new Fixture(111);pulse.start(1020);pulse.send(0,1,10,20,1_001_000_000L);pulse.send(0,0,0,0,1_001_001_000L);pulse.tick();
        check(pulse.motor.starts==1);pulse.start(1040);pulse.tick();pulse.start(1060);pulse.tick();pulse.start(1100);pulse.tick();
        check(pulse.motor.starts==1 && pulse.motor.cancels==1);pulse.stop();
        // Session routing owns one physical motor even though the D1 core still runs.
        Fixture routed=new Fixture(112);routed.start(1000);routed.send(0,1,10,20,routed.clock.ns);
        RumbleDSession session=new RumbleDSession(new RumbleDSession.Source() {
            public long[] poll() {
                routed.batch[4]=routed.seq;routed.batch[7]=routed.batch[10];routed.batch[14]=1;
                routed.stamp[1]=routed.epoch;routed.stamp[2]=routed.seq;routed.stamp[3]=routed.raw[1];
                routed.active.prepare(routed.stamp,routed.batch,out -> false);return routed.raw;
            }
            public void gate(boolean on){routed.active.boundary();}
        },message -> { });
        session.active(routed.active);check(session.attach(routed.motor));session.tick(1000);
        check(routed.motor.starts==1);check(session.stop("pause"));check(routed.motor.cancels==1);
        session.tick(1020);check(routed.motor.starts==1 && routed.motor.cancels==1);
        // Invalid floats fall back safely without generating an output.
        Fixture invalid=new Fixture(113);invalid.start(1000);invalid.send(0,Float.NaN,10,20,invalid.clock.ns);
        invalid.batch[25]=3;invalid.tick();check(invalid.motor.starts==0);invalid.stop();
        // A gap after confirmed decay restores the D1 strength through the same arbiter.
        Fixture degraded=new Fixture(114);degraded.start(1000);degraded.send(0,1,10,20,degraded.clock.ns);degraded.tick();
        degraded.start(1020);degraded.send(0,1,8,16,degraded.clock.ns);degraded.tick();
        degraded.start(1040);degraded.send(0,1,6,12,degraded.clock.ns);degraded.tick();check(degraded.motor.last==38);
        degraded.start(1060);degraded.seq++;degraded.send(0,1,4,8,degraded.clock.ns);degraded.tick();
        check(degraded.motor.last==64 && degraded.motor.starts==3 && degraded.motor.cancels==0);degraded.stop();
        // Expired queued history cannot cause a pulse, while a fresh D1 baseline starts immediately.
        Fixture backlog=new Fixture(115);backlog.start(1200);backlog.send(0,1,10,20,1_000_000_000L);
        backlog.send(0,1,10,20,backlog.clock.ns);backlog.tick();
        check(backlog.active.expired==1 && backlog.motor.starts==1 && backlog.motor.last==64);backlog.stop();
        System.out.println("ACTIVE KEY SAFETY PASS first tick, mapping, zero, double handles, 150ms expiry, gap fallback, generation, ownership transfer, one physical writer, API failure, stale refresh");
    }
}
