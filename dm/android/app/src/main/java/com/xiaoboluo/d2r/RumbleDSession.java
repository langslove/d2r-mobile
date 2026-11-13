package com.xiaoboluo.d2r;

import java.util.function.Consumer;

// 单工作线程会话；Source 可替换为假 JNI 验证异常和生命周期。
// Worker-confined session; a fake JNI source tests exceptions and lifecycle.
final class RumbleDSession {
    interface Source { long[] poll(); void gate(boolean enabled); }
    private final Source source;
    private final Consumer<String> log;
    private final RumbleDOutputCore core;
    private RumbleOutputCore.Motor motor;
    private RumbleDActive active;
    void active(RumbleDActive next) { active=next; }
    private boolean running, needsCancel;
    RumbleDSession(Source source, Consumer<String> log) { this(source,log,new RumbleDCalibration()); }
    RumbleDSession(Source source, Consumer<String> log,RumbleDCalibration calibration) {
        this.source = source; this.log = log;core=new RumbleDOutputCore(calibration);
    }
    void observer(RumbleDOutputCore.Observer observer) { core.observer(observer); }
    boolean stop(String reason) {
        running = false;
        try {
            source.gate(false);
            if (motor != null) {
                if(active!=null) {
                    if(needsCancel) active.fail(motor);
                    else active.stop(motor);
                    core.stop(RumbleDActive.intentOnly());
                }
            }
            needsCancel = false;
        }
        catch (RuntimeException | LinkageError e) { log.accept("stop-error " + reason + " " + e); return false; }
        motor = null; return true;
    }
    boolean attach(RumbleOutputCore.Motor target) {
        if (!stop("attach") || target == null) return false;
        motor = target;
        try { source.gate(true); running = true; return true; }
        catch (RuntimeException | LinkageError e) { log.accept("gate-error " + e); stop("gate-error"); return false; }
    }
    void tick(long now) {
        if (!running || motor == null) return;
        try {
            long[] raw=source.poll();
            if(active!=null) active.tick(now,raw,core,motor);
        }
        catch (RuntimeException | LinkageError e) {
            running = false; log.accept("output-error " + e);
            try { source.gate(false); } catch (RuntimeException | LinkageError ex) { log.accept("gate-error " + ex); }
            needsCancel = true;
            try { if(active!=null) active.fail(motor); needsCancel = false; } catch (RuntimeException ex) { log.accept("cancel-error " + ex); }
        }
    }
}
