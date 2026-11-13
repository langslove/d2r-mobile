// D-only raw exchange, caller holds rumble_monitor_lock. No allocation or I/O.
#pragma once
#include <stdint.h>
#include <string.h>
enum { RD_HEADER=4, RD_ROW=14, RD_WORDS=60 };
typedef struct {
    uint32_t handle, raw[4], peak_raw[4];
    uint64_t seq, updated, peak_seq, peak_time;
    int valid;
    float peak;
} rd_channel;
typedef struct { rd_channel channel[4]; int enabled; } rd_state;
static inline void rd_reset(rd_state *s, int enabled) { memset(s,0,sizeof *s);s->enabled=enabled; }
static inline void rd_update(rd_state *s, unsigned bit, const rp_record *r, int valid) {
    if (!s->enabled) return;
    int i=bit==1?0:bit==2?1:bit==4?2:bit==8?3:-1;
    if (i<0) return;
    rd_channel *c=&s->channel[i];
    c->seq++;c->handle=r->handle;c->updated=r->time_ns;c->valid=valid;
    memcpy(c->raw,r->raw,sizeof c->raw);
    if (!valid) { c->peak=0;c->peak_seq=0;return; }
    float strength=r->value.amp_low>r->value.amp_high?r->value.amp_low:r->value.amp_high;
    if (r->time_ns<c->peak_time || r->time_ns-c->peak_time>=150000000) c->peak=0;
    // Peak is transport protection, NOT a new-event counter. Clear/rewrite is not a trigger.
    if (strength>0 && strength>=c->peak) {
        c->peak=strength;c->peak_time=r->time_ns;c->peak_seq=c->seq;
        memcpy(c->peak_raw,r->raw,sizeof c->peak_raw);
    }
}
static inline void rd_poll(rd_state *s,uint64_t owner,uint64_t now,unsigned allowed,int64_t out[RD_WORDS]) {
    memset(out,0,RD_WORDS*sizeof *out);out[0]=1;out[1]=(int64_t)now;out[2]=(int64_t)owner;out[3]=allowed;
    for (unsigned i=0;i<4;i++) {
        rd_channel *c=&s->channel[i];int64_t *p=out+RD_HEADER+i*RD_ROW;
        if (!s->enabled || !(allowed&(1u<<i))) { memset(c,0,sizeof *c);continue; }
        p[0]=c->handle;p[1]=(int64_t)c->seq;p[2]=(int64_t)c->updated;p[3]=c->valid;
        for (int k=0;k<4;k++) p[4+k]=c->raw[k];
        if (c->peak>0 && now>=c->peak_time && now-c->peak_time<150000000) {
            p[8]=(int64_t)c->peak_seq;p[9]=(int64_t)c->peak_time;
            for (int k=0;k<4;k++) p[10+k]=c->peak_raw[k];
        }
        c->peak=0;c->peak_seq=0;
    }
}
