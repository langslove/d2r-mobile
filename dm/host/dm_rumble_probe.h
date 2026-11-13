// 震动诊断：A 保持原行为；B 仅枚举虚拟设备和采样，绝不驱动马达。ABI 证据见 docs/rumble-probe-abi.md。
// Rumble diagnostics: A preserves behavior; B only enumerates virtual devices and samples, never driving motors. See docs/rumble-probe-abi.md for ABI evidence.
#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdatomic.h>
#include <float.h>
#include <math.h>

enum { RP_PERMIT, RP_HANDLES, RP_INIT, RP_SEND, RP_KINDS };
enum { RP_LIMIT = 128 };
typedef struct { float amp_low, freq_low, amp_high, freq_high; } rp_value;
_Static_assert(sizeof(float) == 4 && FLT_RADIX == 2 && FLT_MANT_DIG == 24 && FLT_MAX_EXP == 128, "binary32 required");
_Static_assert(sizeof(rp_value) == 16 && offsetof(rp_value, freq_low) == 4 &&
               offsetof(rp_value, amp_high) == 8 && offsetof(rp_value, freq_high) == 12, "capture layout");
typedef struct {
    uint64_t time_ns, call;
    int kind, max;
    uint32_t style, id, handle, raw[4];
    int id_ok, handle_ok, value_ok, value_valid, zero;
    rp_value value;
} rp_record;
typedef int (*rp_read_fn)(const void *, void *, size_t);
typedef struct {
    _Atomic uint64_t calls[RP_KINDS];
    atomic_flag busy;
    uint64_t last[RP_KINDS];
    unsigned samples[RP_KINDS];
} rp_state;
#define RP_STATE_INIT { .busy = ATOMIC_FLAG_INIT }

enum { RP_OFF, RP_MODE_A, RP_MODE_B, RP_MODE_C, RP_MODE_D };
// 只有精确的 A/B/C 启用，C 是实验性输出；进程启动后不动态切换。
// Only exact A/B/C enable; C is experimental output. No live mode switching.
static inline int rp_mode(const char *mode) {
#ifdef DM_ANDROID_RUMBLE_D
    if (mode && !strcmp(mode,"D")) return RP_MODE_D;
#endif
    return mode && !strcmp(mode, "A") ? RP_MODE_A : mode && !strcmp(mode, "B") ? RP_MODE_B :
           mode && !strcmp(mode, "C") ? RP_MODE_C : RP_OFF;
}
static inline int rp_enabled(const char *mode) { return rp_mode(mode) != RP_OFF; }

// SDK 22a720/22a768：fullkey index=3，handheld index=4；字节 1 是玩家、字节 2 是逻辑设备号。
// SDK 22a720/22a768: fullkey index=3, handheld index=4; byte 1 is player, byte 2 is logical device index.
static inline int rp_virtual_handles(uint32_t id, uint32_t style, uint32_t connected, uint32_t connected_style, int max, uint32_t out[2]) {
    if (max <= 0 || id != connected || style != connected_style || !out) return 0;
    unsigned index;
    if (id == 0 && style == 1) index = 3;
    else if (id == 0x20 && style == 2) index = 4;
    else return 0;
    out[0] = index | (id << 8);
    if (max > 1) out[1] = out[0] | 0x10000u;
    return max > 1 ? 2 : 1;
}
static inline unsigned rp_handle_bit(uint32_t handle, uint32_t connected, uint32_t style) {
    uint32_t h[2];
    if (!rp_virtual_handles(connected, style, connected, style, 2, h)) return 0;
    unsigned shift = style == 2 ? 2 : 0;
    return handle == h[0] ? 1u << shift : handle == h[1] ? 2u << shift : 0;
}

// B-only monitor. Caller serializes update/snapshot; no allocations or output here.
// Invalid is overlapping: unreadable data, bad/unissued handle or malformed floats.
enum { RP_BUCKETS = 5, RP_EVENT_PER_SECOND = 4 };
typedef struct {
    uint64_t total, all_zero, zero_amp, nonzero, invalid, unreadable, bad_handle, uninitialized;
    uint64_t changes, stops, suppressed;
} rp_counts;
static inline rp_counts rp_add_counts(rp_counts a, rp_counts b) {
#define RP_ADD(field) a.field += b.field
    RP_ADD(total); RP_ADD(all_zero); RP_ADD(zero_amp); RP_ADD(nonzero); RP_ADD(invalid);
    RP_ADD(unreadable); RP_ADD(bad_handle); RP_ADD(uninitialized); RP_ADD(changes); RP_ADD(stops); RP_ADD(suppressed);
#undef RP_ADD
    return a;
}
typedef struct {
    rp_counts counts;
    rp_record first_nonzero;
    uint32_t previous[4];
    uint64_t window_ns;
    unsigned window_events;
    int have_previous, previous_nonzero, have_first;
} rp_channel;
typedef struct {
    rp_channel channel[RP_BUCKETS]; // fullkey L/R, handheld L/R, unknown
    uint64_t last_summary;
    int have_summary;
} rp_monitor;
typedef struct { int emit, first, changed, stop, summary, bucket; } rp_decision;
static inline int rp_bucket(uint32_t handle) {
    switch (handle) {
    case 3: return 0; case 0x10003: return 1;
    case 0x2004: return 2; case 0x12004: return 3;
    default: return 4;
    }
}
static inline int rp_valid_value(rp_value v) {
    return isfinite(v.amp_low) && isfinite(v.freq_low) && isfinite(v.amp_high) && isfinite(v.freq_high) &&
        v.amp_low >= 0 && v.amp_low <= 1 && v.amp_high >= 0 && v.amp_high <= 1 && v.freq_low >= 0 && v.freq_high >= 0;
}
static inline rp_decision rp_observe(rp_monitor *m, const rp_record *r, int issued, int initialized) {
    rp_decision d = {0};
    d.bucket = r->handle_ok ? rp_bucket(r->handle) : 4;
    rp_channel *ch = &m->channel[d.bucket]; rp_counts *c = &ch->counts;
    c->total++;
    int good_handle = r->handle_ok && d.bucket != 4 && issued;
    int nz = r->value_ok && ((isfinite(r->value.amp_low) && r->value.amp_low != 0) ||
                            (isfinite(r->value.amp_high) && r->value.amp_high != 0));
    int zero = r->value_ok && r->value.amp_low == 0 && r->value.amp_high == 0;
    int invalid = !r->value_ok || !good_handle || !r->value_valid;
    c->bad_handle += !good_handle; c->uninitialized += !initialized;
    c->unreadable += !r->value_ok; c->invalid += invalid;
    c->nonzero += nz; c->zero_amp += zero;
    c->all_zero += zero && r->value.freq_low == 0 && r->value.freq_high == 0;
    if (r->value_ok) {
        d.changed = ch->have_previous && memcmp(ch->previous, r->raw, sizeof ch->previous) != 0;
        d.stop = ch->have_previous && ch->previous_nonzero && zero;
        c->changes += d.changed; c->stops += d.stop;
        memcpy(ch->previous, r->raw, sizeof ch->previous);
        ch->have_previous = 1; ch->previous_nonzero = nz;
    } else {
        // A failed read breaks continuity; do not claim the next zero is a stop.
        ch->have_previous = 0;
    }
    if (nz && !ch->have_first) {
        ch->have_first = 1; ch->first_nonzero = *r; d.first = 1;
    }
    if (!ch->window_events || (r->time_ns >= ch->window_ns && r->time_ns - ch->window_ns >= 1000000000ull)) {
        ch->window_ns = r->time_ns; ch->window_events = 0;
    }
    int candidate = nz || d.changed || d.stop || invalid || c->total == 1;
    if (d.first || (candidate && ch->window_events < RP_EVENT_PER_SECOND)) {
        d.emit = 1; ch->window_events++;
    } else if (candidate) c->suppressed++;
    if (!m->have_summary || (r->time_ns >= m->last_summary && r->time_ns - m->last_summary >= 5000000000ull)) {
        m->have_summary = 1; m->last_summary = r->time_ns; d.summary = 1;
    }
    return d;
}

// 前两次加每秒一次，每类最多 128 条；计数仍覆盖限流和并发丢弃的调用。
// First two calls plus one per second, at most 128 records per kind; counts include throttled and contended calls.
static inline int rp_capture(rp_state *s, int enabled, int kind, uint64_t now,
                             const void *handle, const void *value, const void *id,
                             int max, uint32_t style, rp_read_fn read, rp_record *r) {
    if (!enabled || kind < 0 || kind >= RP_KINDS) return 0;
    uint64_t call = atomic_fetch_add_explicit(&s->calls[kind], 1, memory_order_relaxed) + 1;
    if (atomic_flag_test_and_set_explicit(&s->busy, memory_order_acquire)) return 0;
    unsigned n = s->samples[kind];
    int take = n < RP_LIMIT && (n < 2 || (now >= s->last[kind] && now - s->last[kind] >= 1000000000ull));
    if (take) { s->samples[kind]++; s->last[kind] = now; }
    atomic_flag_clear_explicit(&s->busy, memory_order_release);
    if (!take) return 0;
    memset(r, 0, sizeof *r);
    r->kind = kind; r->time_ns = now; r->call = call; r->max = max; r->style = style;
    if (kind == RP_HANDLES && id) r->id_ok = read(id, &r->id, sizeof r->id);
    if ((kind == RP_INIT || kind == RP_SEND) && handle) r->handle_ok = read(handle, &r->handle, sizeof r->handle);
    if (kind == RP_SEND && value) {
        r->value_ok = read(value, &r->value, sizeof r->value);
        if (r->value_ok) {
            memcpy(r->raw, &r->value, sizeof r->raw);
            rp_value v = r->value;
            r->value_valid = isfinite(v.amp_low) && isfinite(v.freq_low) && isfinite(v.amp_high) && isfinite(v.freq_high) &&
                v.amp_low >= 0 && v.amp_low <= 1 && v.amp_high >= 0 && v.amp_high <= 1 && v.freq_low >= 0 && v.freq_high >= 0;
            r->zero = v.amp_low == 0 && v.amp_high == 0;
        }
    }
    return 1;
}
