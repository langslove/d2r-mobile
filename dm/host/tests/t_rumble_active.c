// Related transport regression only: two independent consumers, same atomic snapshot prefix.
#include "../dm_rumble_probe.h"
#include "../dm_rumble_d.h"
#include "../dm_rumble_stream.h"
#define CHECK(x) do { if(!(x)) return __LINE__; } while(0)
int _fltused=0;
void *memset(void *p,int value,size_t n) { unsigned char *b=p;for(size_t i=0;i<n;i++) b[i]=(unsigned char)value;return p; }
void *memcpy(void *p,const void *q,size_t n) { unsigned char *b=p;const unsigned char *a=q;for(size_t i=0;i<n;i++) b[i]=a[i];return p; }
int memcmp(const void *p,const void *q,size_t n) { const unsigned char *a=p,*b=q;for(size_t i=0;i<n;i++) if(a[i]!=b[i])return a[i]<b[i]?-1:1;return 0; }
int strcmp(const char *p,const char *q) { while(*p && *p==*q){p++;q++;}return (unsigned char)*p-(unsigned char)*q; }

static rt_state diagnostic,live;
static rd_state raw_state;
static int64_t batch[RT_WORDS],snapshot[RD_WORDS];
static void send(uint32_t handle,float amp,uint64_t ns) {
    rp_record r={0};r.handle=handle;r.time_ns=ns;r.value=(rp_value){amp,7.5f,amp,12};
    memcpy(r.raw,&r.value,16);rd_update(&raw_state,handle==3?1:2,&r,1);
    rt_put(&diagnostic,ns,handle,r.raw,31);rt_put(&live,ns,handle,r.raw,31);
}
int main(void) {
    rt_reset(&diagnostic,9,1);rt_reset(&live,9,1);rd_reset(&raw_state,1);
    send(3,1,1000000000);send(0x10003,1,1000000001);
    send(3,0,1000001000);send(0x10003,0,1000001001);
    rd_poll(&raw_state,9,1000010000,3,snapshot);
    CHECK(snapshot[5]==2 && snapshot[19]==2 && snapshot[8]==0 && snapshot[12]==1);
    CHECK(rt_peek(&live,9,1000010000,batch)==5 && batch[4]==4 && batch[10]==4);
    CHECK(!rt_commit(&live,8,(uint64_t)batch[2],1,4));
    CHECK(rt_commit(&live,9,(uint64_t)batch[2],1,4));
    CHECK(live.count==0 && diagnostic.count==4);
    CHECK(!rt_commit(&live,9,(uint64_t)batch[2],1,4));
    for(unsigned i=0;i<130;i++)send(3,1,1000020000+i);
    CHECK(rt_peek(&live,9,1000030000,batch)==129 && batch[10]==128 && batch[4]==134);
    CHECK(rt_commit(&live,9,(uint64_t)batch[2],5,128));
    CHECK(rt_peek(&live,9,1000030001,batch)==3 && batch[14]==133);
    uint64_t old_epoch=(uint64_t)batch[2];rt_reset(&live,9,1);
    CHECK(!rt_commit(&live,9,old_epoch,133,2));
    CHECK(diagnostic.count==134 && live.discarded==2 && live.epoch==diagnostic.epoch+1);
    return 0;
}
