package com.xiaoboluo.d2r;

/** Physical output contract shared by the causal envelope and lifecycle policy. */
final class RumbleOutputCore {
    interface Motor { void vibrate(int duration, int amplitude); void cancel(); }
}
