package com.xiaoboluo.d2r;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.InputDevice;
import java.util.Arrays;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One worker, one selected InputDevice vibrator, one output policy. */
final class ControllerRumble implements RumbleDController, InputManager.InputDeviceListener {
    static native boolean nativeMode();
    private static native long nativeClaim();
    private static native void nativeGate(long owner, boolean enabled);
    private static native boolean nativePoll(long owner, long[] raw);
    private static native boolean nativeSoftPoll(long owner, long[] raw, long[] stamp, long[] batch);
    private static native boolean nativeRead(long owner, long[] batch);
    private static native boolean nativeStamp(long owner, long[] stamp);
    private final long owner;
    private final InputManager inputs;
    private final Object outputLock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "dm-controller-rumble");
        thread.setDaemon(true);
        return thread;
    });
    private final long[] raw = new long[60], stamp = new long[4], batch = new long[RumbleDStream.WORDS];
    private final RumbleDSession session;
    private RumbleDActive soft;
    private final RumblePreferenceState settings;
    private volatile boolean foreground, closed;
    private volatile long generation;
    private Motor motor;
    private long lastReport, requests, cancels, errors;

    ControllerRumble(Context context) {
        owner = nativeClaim();
        settings = new RumblePreferences(context).read();
        inputs = context.getApplicationContext().getSystemService(InputManager.class);
        session = new RumbleDSession(new RumbleDSession.Source() {
            public long[] poll() {
                if (nativeSoftPoll(owner, raw, stamp, batch)) {
                    soft.prepare(stamp, batch, rows -> nativeRead(owner, rows));
                } else {
                    soft.readFailure("JNI_POLL");
                    if (!refresh(raw, stamp)) throw new IllegalStateException("D1 fallback unavailable");
                }
                return raw;
            }
            public void gate(boolean enabled) {
                nativeGate(owner, enabled);
                if (soft != null) soft.boundary();
            }
        }, ControllerRumble.this::log);
        worker.setRemoveOnCancelPolicy(true);
        worker.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        worker.scheduleWithFixedDelay(this::tick, 20, 20, TimeUnit.MILLISECONDS);
    }
    private boolean refresh(long[] state, long[] prefix) {
        return nativePoll(owner, state) && nativeStamp(owner, prefix);
    }
    private void log(String message) { Log.i("dm-controller-rumble", message); }
    public void resume() {
        if (closed || foreground) return;
        foreground = true;
        if (inputs != null) inputs.registerInputDeviceListener(this, main);
        invalidate("resume");
    }
    public void pause() {
        foreground = false;
        if (inputs != null) inputs.unregisterInputDeviceListener(this);
        invalidate("pause");
    }
    public void close() {
        if (closed) return;
        pause();
        closed = true;
        worker.execute(() -> { session.stop("close"); });
        worker.shutdown();
    }
    private void invalidate(String reason) {
        if (closed) return;
        final long token;
        synchronized (outputLock) {
            token = ++generation;
            nativeGate(owner, false);
            if (motor != null) try { motor.output.close(); }
            catch (RuntimeException error) { errors++; log("lifecycle cancel error " + error); }
        }
        worker.execute(() -> select(token));
    }
    private boolean current(long token) { return foreground && !closed && generation == token; }
    private void select(long token) {
        if (!session.stop("selection")) return;
        if (!current(token)) return;
        RumblePreferenceState policy = settings;
        soft = new RumbleDActive(owner, System::nanoTime, this::refresh);
        session.active(soft);
        if (!policy.enabled || policy.percent == 0) return;
        try {
            int[] devices = InputDevice.getDeviceIds();
            Arrays.sort(devices);
            int matches = 0;
            for (int id : devices) {
                InputDevice device = InputDevice.getDevice(id);
                if (device != null && ControllerIdentity.mode(device.getName(), device.getVendorId(),
                        device.getProductId(), device.getDescriptor()) != null) matches++;
            }
            if (matches != 1) { log("disabled matching_devices=" + matches); return; }
            for (int id : devices) {
                InputDevice device = InputDevice.getDevice(id);
                if (device == null) continue;
                String mode = ControllerIdentity.mode(device.getName(), device.getVendorId(),
                        device.getProductId(), device.getDescriptor());
                if (mode == null) continue;
                android.os.VibratorManager manager = device.getVibratorManager();
                int[] ids = manager.getVibratorIds();
                Arrays.sort(ids);
                for (int vibratorId : ids) {
                    Vibrator vibrator = manager.getVibrator(vibratorId);
                    if (!vibrator.hasVibrator() || !vibrator.hasAmplitudeControl()) continue;
                    synchronized (outputLock) {
                        if (!current(token)) return;
                        motor = new Motor(device, vibratorId, vibrator, mode, token);
                        if (session.attach(motor)) log("selected " + motor.identity() + " output="
                                + "D2" + " percent=" + policy.percent + " unique_output=1");
                    }
                    return;
                }
            }
            log("disabled no amplitude-controlled InputDevice vibrator");
        } catch (RuntimeException | LinkageError e) {
            errors++; session.stop("discovery"); log("discovery error " + e);
        }
    }
    private void tick() {
        try {
            if (foreground && !closed) session.tick(System.nanoTime() / 1_000_000L);
            long now = SystemClock.uptimeMillis();
            if (now - lastReport >= 5000) {
                lastReport = now;
                log("stats output=" + "D2" + " vibrate=" + requests
                        + " cancel=" + cancels + " errors=" + errors);
            }
        } catch (RuntimeException | LinkageError e) { errors++; session.stop("tick"); log("tick error " + e); }
    }
    public void onInputDeviceAdded(int id) { invalidate("added"); }
    public void onInputDeviceChanged(int id) { invalidate("changed"); }
    public void onInputDeviceRemoved(int id) { invalidate("removed"); }
    private final class Motor implements RumbleOutputCore.Motor {
        final int deviceId, vibratorId;
        final String descriptor, mode;
        final Vibrator vibrator;
        final long token;
        final RumbleOutputPolicy output;
        Motor(InputDevice device, int vibratorId, Vibrator vibrator, String mode, long token) {
            this.deviceId = device.getId(); this.descriptor = device.getDescriptor();
            this.vibratorId = vibratorId; this.vibrator = vibrator; this.mode = mode; this.token = token;
            output = new RumbleOutputPolicy(outputLock, new RumbleOutputCore.Motor() {
                public void vibrate(int ms, int amplitude) { physicalVibrate(ms, amplitude); }
                public void cancel() { vibrator.cancel(); cancels++; }
            }, settings);
        }
        String identity() { return "mode=" + mode + " device=" + deviceId + " vibrator=" + vibratorId + " descriptor=" + descriptor; }
        public void vibrate(int ms, int amplitude) { output.vibrate(ms, amplitude); }
        private void physicalVibrate(int ms, int amplitude) {
            synchronized (outputLock) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (!current(token) || device == null || !ControllerIdentity.same(mode, descriptor,
                        device.getName(), device.getVendorId(), device.getProductId(), device.getDescriptor()))
                    throw new IllegalStateException("stale controller");
                int scaled = amplitude;
                vibrator.vibrate(VibrationEffect.createOneShot(ms, scaled));
                requests++;
            }
        }
        public void cancel() {
            output.cancel();
        }
    }
}
