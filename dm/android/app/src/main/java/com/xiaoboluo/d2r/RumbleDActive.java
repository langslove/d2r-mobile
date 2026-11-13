package com.xiaoboluo.d2r;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Live D2 selection and the sole physical output arbiter, confined to the D1 worker. */
final class RumbleDActive {
    interface Reader { boolean read(long[] batch); }
    interface Refresh { boolean read(long[] raw,long[] stamp); }
    private static final RumbleOutputCore.Motor INTENT_ONLY=new RumbleOutputCore.Motor() {
        public void vibrate(int ms,int amplitude) { }
        public void cancel() { }
    };
    // Ownership transfer and every physical call share this monitor. Old owners cannot cancel new output.
    private static final class Output {
        long owner;
        RumbleOutputCore.Motor motor;
        RumbleCausalModel.Clock clock=new RumbleCausalModel.Clock();
        synchronized void claim(long next) { owner=next; }
        synchronized int tick(long caller,RumbleOutputCore.Motor target,long now,int[] choices) {
            if(caller!=owner) return 0;
            int calls=0;
            if(motor!=target) {
                if(clock.active && motor!=null) { motor.cancel();calls|=2; }
                clock=new RumbleCausalModel.Clock();motor=target;
            }
            clock.tick(now,choices);
            if(clock.op==1) { motor.vibrate(clock.duration,clock.amplitude);calls|=1; }
            else if(clock.op==2) { motor.cancel();calls|=2; }
            return calls;
        }
        synchronized boolean stop(long caller,long now,boolean force) {
            if(caller!=owner) return false;
            boolean cancel=motor!=null && (clock.active || force);
            if(cancel) motor.cancel();
            clock=new RumbleCausalModel.Clock();motor=null;
            return cancel;
        }
    }
    static RumbleOutputCore.Motor intentOnly() { return INTENT_ONLY; }
    private static final Output OUTPUT=new Output();
    private final long owner;
    private final LongSupplier clock;
    private final Refresh refresh;
    private final RumbleDStream coverage;
    private final long[] events=new long[4096*10],event=new long[10],stamp=new long[4];
    private final long[] current=new long[60];
    private final int[] selected=new int[8];
    private RumbleCausalModel model=new RumbleCausalModel();
    private int head,count;
    private long epoch,received,consumed,watermark,snapshotTime,lastNow;
    private boolean bad=true,baselineOnly;
    private volatile long revision;
    private long seenRevision=-1;
    String reason="STARTUP";
    long polls,fallbacks,expired,readFailures,gaps,overflows,vibrateCalls,cancelCalls,nativeLost,nativeFaults;
    long candidateRequests,candidateCancels,baselineRequests,baselineCancels;
    int target;

    RumbleDActive(long owner,LongSupplier clock,Refresh refresh) {
        this.owner=owner;this.clock=clock;this.refresh=refresh;coverage=new RumbleDStream(owner);OUTPUT.claim(owner);
    }
    synchronized void boundary() { revision++; }
    private void reset(long nextEpoch) {
        epoch=nextEpoch;received=0;consumed=0;head=0;count=0;model=new RumbleCausalModel();bad=false;
    }
    void readFailure(String why) { readFailures++;reject(why); }
    void reject(String why) { bad=true;reason=why; }
    private boolean validStamp(long[] s) { return s!=null && s.length==4 && s[0]==owner && s[1]!=0; }
    void prepare(long[] sourceStamp,long[] firstBatch,Reader reader) {
        if(!validStamp(sourceStamp)) { reject("STAMP");return; }
        if(seenRevision!=revision || epoch!=sourceStamp[1]) {
            seenRevision=revision;reset(sourceStamp[1]);
        }
        System.arraycopy(sourceStamp,0,stamp,0,4);watermark=stamp[2];snapshotTime=stamp[3];baselineOnly=false;
        accept(firstBatch);
        for(int reads=1;Long.compareUnsigned(received,watermark)<0 && reads<4;reads++) {
            if(!reader.read(firstBatch)) { readFailures++;reject("READ_FAILURE");break; }
            accept(firstBatch);
        }
        if(Long.compareUnsigned(received,watermark)<0) { gaps++;reject("INCOMPLETE_PREFIX"); }
    }
    private void accept(long[] batch) {
        nativeLost=Math.max(nativeLost,batch[5]);nativeFaults|=batch[8];
        if(!coverage.accept(batch) || !coverage.trustworthy() || batch[1]!=owner || batch[2]!=epoch) {
            reject("COVERAGE");return;
        }
        for(int i=0;i<batch[10];i++) {
            int at=20+i*10;long seq=batch[at+2];
            if(seq!=received+1) { gaps++;reject("SEQUENCE_GAP"); }
            received=seq;
            if(count==4096) { overflows++;reject("JAVA_QUEUE_FULL");continue; }
            System.arraycopy(batch,at,events,((head+count)%4096)*10,10);count++;
        }
    }
    void tick(long originalMs,long[] raw,RumbleDOutputCore baseline,RumbleOutputCore.Motor motor) {
        polls++;long now=clock.getAsLong();
        if(raw==null || raw.length!=60 || raw[0]!=1 || raw[2]!=owner || (raw[3]&~15L)!=0 || now<lastNow || now/1_000_000L<originalMs) {
            reject("CLOCK_OR_SNAPSHOT");stop(motor);return;
        }
        if(now<raw[1] || now-raw[1]>=40_000_000L) {
            expired++;baselineOnly=true;reason="STALE_CANDIDATE";
            if(refresh==null || !refresh.read(raw,stamp)) { readFailure("REFRESH_FAILURE");stop(motor);return; }
            now=clock.getAsLong();
            if(raw[0]!=1 || raw[2]!=owner || !validStamp(stamp) || stamp[3]!=raw[1]
                    || now<raw[1] || now-raw[1]>=40_000_000L) { reject("STALE_BASELINE");stop(motor);return; }
            if(stamp[1]!=epoch) { reset(stamp[1]);reject("REFRESH_EPOCH"); }
        }
        lastNow=now;long nowMs=now/1_000_000L;
        // Existing D1 calculates and traces its intent but never receives the physical motor here.
        baseline.tick(nowMs,raw,INTENT_ONLY);
        if(raw[1]!=snapshotTime && !baselineOnly) reject("SNAPSHOT_MISMATCH");
        if(seenRevision!=revision) reject("GENERATION");
        while(count>0 && Long.compareUnsigned(events[head*10+2],watermark)<=0) {
            System.arraycopy(events,head*10,event,0,10);head=(head+1)%4096;count--;
            if(event[2]!=consumed+1) { gaps++;reject("CONSUME_GAP"); }
            consumed=event[2];
            if(event[0]!=owner || event[1]!=epoch || event[3]>raw[1]) reject("EVENT_EPOCH_OR_FUTURE");
            if(now-event[3]>=150_000_000L) { expired++;reject("EXPIRED_HISTORY"); }
            if(!bad) model.feed(event[3],event[4],event,6,event[5]==31);
        }
        if(consumed!=watermark) reject("WATERMARK");
        if(!bad) for(int i=0;i<4;i++) {
            if((raw[3]&(1L<<i))!=0 && model.sequences[i]!=raw[5+i*14]) reject("CHANNEL_PREFIX");
        }
        // Freshness uses the current monotonic clock, including time spent in JNI/GC.
        System.arraycopy(raw,0,current,0,60);current[1]=now;
        model.poll(now,nowMs,current);
        if(model.d1.op==1) baselineRequests++;
        else if(model.d1.op==2) baselineCancels++;
        if(!bad && !baselineOnly && seenRevision==revision) {
            if(model.candidate.op==1) candidateRequests++;
            else if(model.candidate.op==2) candidateCancels++;
        }
        boolean fallback=bad || baselineOnly || seenRevision!=revision;
        if(fallback) fallbacks++;
        System.arraycopy(fallback?model.baseline.choices:model.choices,0,selected,0,8);
        target=0;
        for(int i=0;i<4;i++) {
            int b=4+i*14;
            long updated=raw[b+2],peak=raw[b+9];
            long freshest=Math.max(updated,peak);
            if(freshest<=0 || now<freshest || now-freshest>=150_000_000L) {
                selected[2*i]=0;selected[2*i+1]=0;
            }
            target=Math.max(target,selected[2*i]);
        }
        int op=OUTPUT.tick(owner,motor,nowMs,selected);
        if((op&1)!=0) vibrateCalls++;
        if((op&2)!=0) cancelCalls++;
        if(!fallback) reason="LIVE_CAUSAL";
    }
    void stop(RumbleOutputCore.Motor ignored) {
        if(OUTPUT.stop(owner,clock.getAsLong()/1_000_000L,false)) cancelCalls++;
        target=0;baselineOnly=true;bad=true;head=0;count=0;model=new RumbleCausalModel();
    }
    void fail(RumbleOutputCore.Motor ignored) {
        if(OUTPUT.stop(owner,clock.getAsLong()/1_000_000L,true)) cancelCalls++;
        bad=true;target=0;
    }
    String summary() {
        return "D2A stats owner="+owner+" epoch="+epoch+" event_seq="+consumed+" watermark="+watermark
                +" polls="+polls+" target="+target+" fallback_d1="+(bad || baselineOnly || seenRevision!=revision)
                +" reason="+reason+" candidate_vibrate="+candidateRequests+" candidate_cancel="+candidateCancels
                +" baseline_vibrate="+baselineRequests+" baseline_cancel="+baselineCancels
                +" actual_vibrate="+vibrateCalls+" actual_cancel="+cancelCalls+" expired="+expired
                +" native_lost="+nativeLost+" native_faults="+nativeFaults+" read_failures="+readFailures+" gaps="+gaps+" overflows="+overflows;
    }
}
