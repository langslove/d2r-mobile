package com.xiaoboluo.d2r;

/** Final amplitude control and synchronous cancellation, shared by both algorithms. */
final class RumbleOutputPolicy implements RumbleOutputCore.Motor {
    private final Object lock;
    private final RumbleOutputCore.Motor device;
    private RumblePreferenceState state;
    private boolean closed;
    RumbleOutputPolicy(Object lock, RumbleOutputCore.Motor device, RumblePreferenceState state) {
        this.lock = lock;
        this.device = device;
        this.state = state;
    }
    void update(RumblePreferenceState next) {
        synchronized (lock) {
            state = next;
            device.cancel();
        }
    }
    void close() {
        synchronized (lock) { closed = true; device.cancel(); }
    }
    public void vibrate(int duration, int amplitude) {
        synchronized (lock) {
            if (closed) return;
            int scaled = state.amplitude(amplitude);
            if (scaled == 0) return;
            device.vibrate(duration, scaled);
        }
    }
    public void cancel() {
        synchronized (lock) { device.cancel(); }
    }
}
