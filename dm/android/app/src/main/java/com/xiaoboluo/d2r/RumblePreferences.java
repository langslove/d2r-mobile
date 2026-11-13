package com.xiaoboluo.d2r;

import android.content.Context;
import android.content.SharedPreferences;

final class RumblePreferences {
    private final SharedPreferences store;
    RumblePreferences(Context context) {
        this(context.getSharedPreferences("controller_rumble", Context.MODE_PRIVATE));
    }
    RumblePreferences(SharedPreferences store) { this.store = store; }
    RumblePreferenceState read() {
        return new RumblePreferenceState(store.getBoolean("enabled", true),
                store.getInt("percent", 100));
    }
}
