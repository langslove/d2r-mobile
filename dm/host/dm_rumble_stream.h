// Debug D2-T transport only. Every operation requires rumble_monitor_lock.
#pragma once
#include <stdint.h>
#include <string.h>
#include <limits.h>

enum {
    RT_CAPACITY = 4096, RT_BATCH = 128, RT_HEADER = 20, RT_ROW = 10,
    RT_WORDS = RT_HEADER + RT_BATCH * RT_ROW
};
enum { RT_OVERFLOW = 1, RT_SEQUENCE_END = 2, RT_CLOCK_BACKWARD = 4 };

typedef struct { uint64_t word[RT_ROW]; } rt_event;
typedef struct {
    rt_event events[RT_CAPACITY];
    uint64_t owner, epoch, sequence, lost, delivered, discarded, last_time;
    uint64_t total_writes, total_lost, previous_sequence, previous_lost;
    unsigned head, count, faults;
    int enabled, exhausted;
} rt_state;

// Epochs never silently wrap. Exhaustion disables capture until process restart.
static inline void rt_reset(rt_state *s, uint64_t owner, int enabled) {
    s->discarded = UINT64_MAX - s->discarded < s->count ? UINT64_MAX : s->discarded + s->count;
    s->previous_sequence = s->sequence;
    s->previous_lost = s->lost;
    s->head = 0;
    s->count = 0;
    if (s->epoch == UINT64_MAX) s->exhausted = 1;
    else s->epoch++;
    s->owner = owner;
    s->sequence = 0;
    s->lost = 0;
    s->delivered = 0;
    s->last_time = 0;
    s->faults = s->exhausted ? RT_SEQUENCE_END : 0;
    s->enabled = enabled && !s->exhausted;
}

static inline void rt_put(rt_state *s, uint64_t ns, uint32_t handle, const uint32_t raw[4], unsigned flags) {
    if (!s->enabled) return;
    if (s->sequence == UINT64_MAX) {
        s->faults |= RT_SEQUENCE_END;
        s->enabled = 0;
        return;
    }
    uint64_t seq = ++s->sequence;
    if (s->total_writes != UINT64_MAX) s->total_writes++;
    if (ns < s->last_time) s->faults |= RT_CLOCK_BACKWARD;
    s->last_time = ns;
    if (s->count == RT_CAPACITY) {
        if (s->lost != UINT64_MAX) s->lost++;
        if (s->total_lost != UINT64_MAX) s->total_lost++;
        s->faults |= RT_OVERFLOW;
        return;
    }
    rt_event *event = &s->events[(s->head + s->count) % RT_CAPACITY];
    event->word[0] = s->owner;
    event->word[1] = s->epoch;
    event->word[2] = seq;
    event->word[3] = ns;
    event->word[4] = handle;
    event->word[5] = flags;
    for (unsigned k = 0; k < 4; k++) event->word[6 + k] = raw[k];
    s->count++;
}

// Peek does not consume. A failed Java array copy leaves this batch retryable.
static inline unsigned rt_peek(const rt_state *s, uint64_t owner, uint64_t now, int64_t out[RT_WORDS]) {
    if (!owner || owner != s->owner) return 0;
    unsigned n = s->count < RT_BATCH ? s->count : RT_BATCH;
    out[0] = 1;
    out[1] = (int64_t)s->owner;
    out[2] = (int64_t)s->epoch;
    out[3] = s->enabled;
    out[4] = (int64_t)s->sequence;
    out[5] = (int64_t)s->lost;
    out[6] = (int64_t)s->delivered;
    out[7] = s->count;
    out[8] = s->faults;
    out[9] = (int64_t)now;
    out[10] = n;
    out[11] = (int64_t)s->discarded;
    out[12] = RT_CAPACITY;
    out[13] = RT_ROW;
    out[14] = n ? (int64_t)s->events[s->head].word[2] : 0;
    out[15] = RT_HEADER;
    out[16] = (int64_t)s->total_writes;
    out[17] = (int64_t)s->total_lost;
    out[18] = (int64_t)s->previous_sequence;
    out[19] = (int64_t)s->previous_lost;
    for (unsigned i = 0; i < n; i++) {
        memcpy(out + RT_HEADER + i * RT_ROW, &s->events[(s->head + i) % RT_CAPACITY], sizeof(rt_event));
    }
    return n + 1;
}

static inline int rt_commit(rt_state *s, uint64_t owner, uint64_t epoch, uint64_t first, unsigned n) {
    if (!owner || owner != s->owner || epoch != s->epoch || n > s->count || n > RT_BATCH) return 0;
    if (n && first != s->events[s->head].word[2]) return 0;
    s->head = (s->head + n) % RT_CAPACITY;
    s->count -= n;
    s->delivered += n;
    return 1;
}
