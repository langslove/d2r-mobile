"""OFFLINE ONLY. No Android/JNI/adb imports. Every state transition uses one delivered sample or clock tick.
The D1 mirror is checked against real native + Java D1 replay, not used by the app.
"""
from collections import OrderedDict
from dataclasses import dataclass, asdict
import math
import struct

NS=1_000_000
HANDLES=(3,0x10003,0x2004,0x12004)
def floats(words):return struct.unpack('<4f',struct.pack('<4I',*words))
def bits(value):return struct.unpack('<I',struct.pack('<f',value))[0]
def amplitude(a,b):
    if not all(math.isfinite(v) and 0<=v<=1 for v in (a,b)):return 0
    return min(128,math.floor(64*max(a,b)+.5))
def ttl(now,updated):return max(0,(150*NS-(now-updated))//NS) if updated>0 and now>=updated else 0
def valid(raw,b,offset):
    try:v=floats(raw[b+offset:b+offset+4])
    except (ValueError,struct.error):return False
    return all(math.isfinite(x) and x>=0 and (i%2 or x<=1) for i,x in enumerate(v))

@dataclass(frozen=True)
class Config:
    enabled:bool=True
    separate_pulse:bool=True
    floor:int=0
    keep_nonzero:bool=True
    falls:int=2
    relative_step:float=.01
    cumulative_drop:float=.05
    ratio_tolerance:float=.05
    epsilon:float=1e-5
    min_sample_ms:int=5
    max_sample_ms:int=40
    quiet_ms:int=60
    stream_bridge_ms:int=20
    bridge_effect_ms:int=40
    require_contiguous:bool=True
    def __post_init__(self):
        if not (all(math.isfinite(x) for x in (self.relative_step,self.cumulative_drop,self.ratio_tolerance,self.epsilon))
                and 0<self.ratio_tolerance<1 and 0<self.epsilon<.01
                and self.require_contiguous and self.falls>=2 and 0<=self.floor<=64 and 0<self.relative_step<1 and 0<self.cumulative_drop<1
                and 0<self.min_sample_ms<=self.max_sample_ms<150 and 0<self.quiet_ms<150
                and 0<self.stream_bridge_ms<=40 and self.stream_bridge_ms<=self.bridge_effect_ms<=60):
            raise ValueError('unsafe offline parameters')

class Channel:
    def __init__(self,handle,config):
        self.handle,self.cfg=handle,config
        self.seq=None;self.updated=0;self.last_raw=None;self.last_positive=0;self.zero_since=None
        self.event=0;self.status='IDLE';self.phase='BASELINE';self.reference=None;self.previous=None;self.last_value=None
        self.previous_time=0;self.streak=0;self.samples=0;self.ever_confirmed=False;self.first_confirmed=None
        self.cache=OrderedDict();self.diagnostics=[];self.fallbacks=0;self.confirmations=0;self.evictions=0
    def log(self,time,reason,**values):
        self.diagnostics.append(dict(ns=time,handle=hex(self.handle),event=self.event,seq=self.seq,
            status=self.status,phase=self.phase,reason=reason,**values))
    def fallback(self,time,reason):
        if self.phase!='FALLBACK':self.fallbacks+=1;self.phase='FALLBACK';self.log(time,reason)
    def advance(self,now):
        if self.updated and now-self.updated>=150*NS and self.status not in ('IDLE','ENDED','TIMEOUT','INVALID'):
            self.status='TIMEOUT';self.log(now,'TIMEOUT')
        elif self.zero_since is not None and now-self.zero_since>=self.cfg.quiet_ms*NS and self.status=='ZERO_PENDING':
            self.status='ENDED';self.log(now,'QUIET_END')
    def feed(self,time,seq,words,readable=True):
        self.advance(time)
        if time<self.updated or (self.seq is not None and seq<self.seq):
            self.status='INVALID';self.fallback(time,'OUT_OF_ORDER');return
        if self.seq==seq:
            if time==self.updated and tuple(words)==self.last_raw:return
            self.status='INVALID';self.fallback(time,'CONFLICTING_DUPLICATE');return
        gap=self.seq is not None and seq!=self.seq+1
        self.seq=seq;self.updated=time;self.last_raw=tuple(words)
        try:v=floats(words)
        except (struct.error,ValueError):v=(float('nan'),)*4
        if not readable or not all(math.isfinite(x) and x>=0 and (i%2 or x<=1) for i,x in enumerate(v)):
            self.status='INVALID';self.fallback(time,'INVALID_VALUE');return
        u=max(v[0],v[2])
        if u==0:
            if gap and self.cfg.require_contiguous and self.status not in ('IDLE','ENDED','TIMEOUT','INVALID'):
                self.fallback(time,'SEQUENCE_GAP')
            if self.status not in ('IDLE','ENDED','TIMEOUT','INVALID'):
                if self.zero_since is None:
                    self.zero_since=time;self.status='ZERO_PENDING';self.log(time,'ZERO_PENDING')
                self.status='ZERO_PENDING'
            return
        new=self.status in ('IDLE','ENDED','TIMEOUT','INVALID')
        if new:
            self.event+=1;self.status='ACTIVE';self.phase='BASELINE';self.reference=list(v);self.previous=None;self.last_value=None
            self.previous_time=0;self.streak=0;self.samples=0;self.ever_confirmed=False;self.first_confirmed=None
            self.log(time,'NEW_ENVELOPE')
        elif self.zero_since is not None:self.log(time,'TEMP_ZERO_RESUME',zero_ns=time-self.zero_since)
        self.status='ACTIVE';self.zero_since=None;self.last_positive=time
        if gap and self.cfg.require_contiguous:
            if new:self.log(time,'COVERAGE_RESET_AT_ONSET')
            else:self.fallback(time,'SEQUENCE_GAP')
        if v[0]<=0 or v[2]<=0 or v[1]<=self.cfg.epsilon or v[3]<=self.cfg.epsilon:
            self.fallback(time,'INSUFFICIENT_BANDS')
        if self.phase!='FALLBACK' and self.previous is not None:
            dt=time-self.previous_time
            if dt>self.cfg.max_sample_ms*NS:self.fallback(time,'SAMPLE_GAP')
            elif abs(v[0]-self.reference[0])>self.cfg.epsilon or abs(v[2]-self.reference[2])>self.cfg.epsilon:
                self.fallback(time,'AMPLITUDE_STRUCTURE_CHANGED')
            elif any(v[k]>self.last_value[k]+max(self.cfg.epsilon,self.last_value[k]*self.cfg.epsilon) for k in (1,3)):
                self.fallback(time,'NON_MONOTONIC')
            elif abs(v[1]/self.reference[1]-v[3]/self.reference[3])>self.cfg.ratio_tolerance:
                self.fallback(time,'BAND_SHAPE_MISMATCH')
            elif dt>=self.cfg.min_sample_ms*NS:
                falling=[v[k]<self.previous[k]*(1-self.cfg.relative_step) for k in (1,3)]
                if all(falling):self.streak+=1;self.phase='PROBING' if self.phase!='CONFIRMED' else self.phase
                elif any(falling):self.fallback(time,'UNPAIRED_FALL')
                # A plateau neither adds evidence nor invalidates a short, observed plateau.
        if self.phase!='FALLBACK':
            self.reference=[max(a,b) if i%2 else a for i,(a,b) in enumerate(zip(self.reference,v))]
            self.samples+=1
            q=max(v[1]/self.reference[1],v[3]/self.reference[3])
            if self.streak>=self.cfg.falls and q<=1-self.cfg.cumulative_drop and self.samples>=3:
                if self.phase!='CONFIRMED':
                    self.phase='CONFIRMED';self.ever_confirmed=True;self.first_confirmed=time;self.confirmations+=1
                    self.log(time,'CONFIRMED',reference=list(self.reference),streak=self.streak)
        else:q=1
        baseline=amplitude(v[0],v[2]);mapped=baseline
        if self.phase=='CONFIRMED':
            q=max(v[0]*v[1]/self.reference[1],v[2]*v[3]/self.reference[3]);q=max(0,min(1,q))
            mapped=math.floor(64*q+.5)
            if q>0 and self.cfg.keep_nonzero:mapped=max(1,mapped)
            if q>0 and self.cfg.floor:mapped=max(self.cfg.floor,mapped)
        self.cache[(time,tuple(words))]=(mapped,self.phase,self.event)
        if len(self.cache)>128:self.cache.popitem(last=False);self.evictions+=1
        self.log(time,'POSITIVE',floats=list(v),baseline=baseline,mapped=mapped,reference=list(self.reference),streak=self.streak)
        if self.previous is None or time-self.previous_time>=self.cfg.min_sample_ms*NS:
            self.previous=v;self.previous_time=time
        self.last_value=v
    def map_sample(self,time,words,observed_now=None):
        baseline=amplitude(*floats(words)[::2])
        if max(floats(words)[::2])==0:return 0
        if self.phase!='CONFIRMED':return baseline
        item=self.cache.get((time,tuple(words)))
        if item is None or item[2]!=self.event:
            self.fallback(self.updated if observed_now is None else observed_now,'MISSING_HISTORY');return baseline
        return item[0]

class LegacyPolicy:
    """Exact D1 policy mirror, validated against actual Java on every reference case."""
    def __init__(self):self.held=[0]*4;self.until=[0]*4
    def reset(self):self.held=[0]*4;self.until=[0]*4
    def choices(self,now,raw):
        if len(raw)!=60 or raw[0]!=1 or raw[1]<0 or raw[3]&~15:self.reset();return [(0,0)]*4
        choices=[]
        for i in range(4):
            b=4+i*14
            if not raw[3]&(1<<i) or raw[b+1]==0 or raw[b]!=HANDLES[i] or raw[b+3]!=1 or not valid(raw,b,4):
                self.held[i]=0;self.until[i]=0;choices.append((0,0));continue
            life=ttl(raw[1],raw[b+2]);v=floats(raw[b+4:b+8]);current=amplitude(v[0],v[2]) if life>0 else 0
            peaklife=ttl(raw[1],raw[b+9]);peak=0
            if raw[b+8]>0 and peaklife>0 and valid(raw,b,10):
                v=floats(raw[b+10:b+14]);peak=amplitude(v[0],v[2])
            if peak>current and (now>=self.until[i] or peak>=self.held[i]):self.held[i]=peak;self.until[i]=now+min(40,peaklife)
            value=current
            if self.until[i]>now and self.held[i]>=value:
                life=self.until[i]-now if self.held[i]>value else max(life,self.until[i]-now);value=self.held[i]
            choices.append((value,life))
        return choices

class OutputClock:
    def __init__(self):self.active=False;self.last=0;self.started=0;self.effect_until=0;self.events=[];self.decisions=[]
    def stop(self,now,reason):
        if self.active:self.events.append([now,'cancel',0,0]);self.active=False;self.last=0
        self.decisions.append(dict(ms=now,target=0,reason=reason))
    def tick(self,now,choices):
        value,life=max(choices,key=lambda c:(c[0],c[1]))
        if value==0 or life<=0:self.stop(now,'ZERO');return False
        if self.active and now-self.started<20:self.decisions.append(dict(ms=now,target=value,reason='THROTTLE'));return True
        if not self.active or value!=self.last or now-self.started>=40 or now>=self.effect_until:
            duration=min(60,life);self.events.append([now,'vibrate',value,duration]);self.active=True;self.last=value
            self.started=now;self.effect_until=now+duration;reason='VIBRATE'
        else:reason='SAME_SKIP'
        self.decisions.append(dict(ms=now,target=value,reason=reason));return True

class D1Mirror:
    def __init__(self):self.policy=LegacyPolicy();self.output=OutputClock()
    def poll(self,now_ns,raw,output_ms=None):
        now=now_ns//NS if output_ms is None else output_ms;choices=self.policy.choices(now,raw)
        if not self.output.tick(now,choices):self.policy.reset()
        return choices

class Simulator:
    def __init__(self,config=Config()):
        self.cfg=config;self.channels={h:Channel(h,config) for h in HANDLES};self.shadow=D1Mirror()
        self.output=OutputClock();self.proxy_legacy=LegacyPolicy();self.bridge=[(0,0,0)]*4
        self.active=True;self.fresh_after=0;self.timeline=[]
    def lifecycle(self,now_ns,active,reason):
        self.output.stop(now_ns//NS,reason);self.shadow.output.stop(now_ns//NS,reason)
        self.shadow.policy.reset();self.proxy_legacy.reset();self.bridge=[(0,0,0)]*4
        self.channels={h:Channel(h,self.cfg) for h in HANDLES};self.active=active;self.fresh_after=now_ns
        self.timeline.append(dict(ns=now_ns,reason=reason,active=active))
    def feed(self,time,handle,seq,words,valid_sample=True):
        if not self.active:return
        if handle not in self.channels:self.timeline.append(dict(ns=time,reason='INVALID_HANDLE',handle=handle));return
        self.channels[handle].feed(time,seq,words,valid_sample)
    def poll(self,now_ns,raw,output_ms=None):
        now=now_ns//NS if output_ms is None else output_ms
        if not self.active:self.output.stop(now,'PAUSED');return
        if len(raw)!=60 or raw[0]!=1 or raw[1]>now_ns or now_ns-raw[1]>=150*NS:
            self.lifecycle(now_ns,False,'INVALID_SNAPSHOT');return
        baseline=self.shadow.poll(now_ns,raw,output_ms)
        if not self.cfg.enabled:
            self.output.tick(now,baseline);return
        transformed=list(raw)
        for i,handle in enumerate(HANDLES):
            b=4+i*14;c=self.channels[handle];c.advance(now_ns)
            for offset,timeoffset in ((4,2),(10,9)):
                words=raw[b+offset:b+offset+4]
                if valid(raw,b,offset):
                    mapped=c.map_sample(raw[b+timeoffset],words,now_ns)
                    transformed[b+offset]=transformed[b+offset+2]=bits(mapped/64)
        legacy_proxy=self.proxy_legacy.choices(now,transformed);choices=[]
        for i,handle in enumerate(HANDLES):
            b=4+i*14;c=self.channels[handle];reason='D1_FALLBACK';choice=baseline[i]
            if not raw[3]&(1<<i) or raw[b+1]==0 or raw[b]!=handle or raw[b+3]!=1 or not valid(raw,b,4):
                choice=(0,0);self.bridge[i]=(0,0,0);reason='TRANSPORT_INVALID'
            elif c.status in ('INVALID','TIMEOUT') or (self.fresh_after and raw[b+2]<=self.fresh_after):
                choice=(0,0);self.bridge[i]=(0,0,0);reason=c.status
            elif c.phase=='CONFIRMED':
                if not self.cfg.separate_pulse:choice=legacy_proxy[i];reason='PROXY_LEGACY_HOLD'
                elif c.status=='ENDED':choice=(0,0);self.bridge[i]=(0,0,0);reason='CONFIRMED_END'
                else:
                    v=floats(raw[b+4:b+8]);life=ttl(raw[1],raw[b+2]);peaklife=ttl(raw[1],raw[b+9])
                    current=c.map_sample(raw[b+2],raw[b+4:b+8],now_ns) if max(v[0],v[2])>0 and life else 0
                    if current:
                        choice=(current,life);self.bridge[i]=(0,0,0);reason='CURRENT_DECAY'
                    elif raw[b+8]>0 and peaklife and valid(raw,b,10):
                        mapped=c.map_sample(raw[b+9],raw[b+10:b+14],now_ns)
                        self.bridge[i]=(mapped,now+min(self.cfg.stream_bridge_ms,peaklife),raw[b+9])
                        choice=(mapped,min(self.cfg.bridge_effect_ms,peaklife));reason='STREAM_BRIDGE'
                    else:
                        amp,until,updated=self.bridge[i];remaining=ttl(now_ns,updated)
                        choice=(amp,min(self.cfg.bridge_effect_ms,remaining)) if until>now and remaining else (0,0)
                        reason='STREAM_BRIDGE_RETAIN' if choice[0] else 'ZERO'
            self.timeline.append(dict(ns=now_ns,handle=hex(handle),event=c.event,seq=c.seq,status=c.status,
                phase=c.phase,target=choice[0],ttl=choice[1],reason=reason))
            choices.append(choice)
        if not self.output.tick(now,choices):self.proxy_legacy.reset();self.bridge=[(0,0,0)]*4
    def diagnostics(self):
        return dict(parameters=asdict(self.cfg),channels={hex(h):dict(confirmations=c.confirmations,
            fallbacks=c.fallbacks,history_evictions=c.evictions,events=c.event,records=c.diagnostics) for h,c in self.channels.items()},polls=self.timeline)
