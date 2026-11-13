package com.xiaoboluo.d2r;

/** Immutable user policy, applied after either envelope algorithm. */
final class RumblePreferenceState {
    final boolean enabled;
    final int percent;
    RumblePreferenceState(boolean enabled, int percent) {
        this.enabled = enabled;
        this.percent = Math.max(0, Math.min(100, (percent + 2) / 5 * 5));
    }
    int amplitude(int input) {
        if (!enabled || percent == 0 || input <= 0) return 0;
        return Math.min(128, Math.max(0, input)) * percent / 100;
    }
}
