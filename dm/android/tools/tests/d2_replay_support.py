"""Offline test helpers. Loads the existing test-only DLL, never a device/native app library."""
import ctypes
from pathlib import Path
from d2_causal_model import NS,bits

BASE=1_000_000_000
ROOT=Path(__file__).resolve().parents[4]
class Native:
    def __init__(self,path):
        self.lib=ctypes.CDLL(str(path))
        self.lib.replay_reset.argtypes=[];self.lib.replay_reset.restype=None
        self.lib.replay_send.argtypes=[ctypes.c_uint32,ctypes.POINTER(ctypes.c_uint32),ctypes.c_uint64];self.lib.replay_send.restype=None
        self.lib.replay_poll.argtypes=[ctypes.c_uint64,ctypes.POINTER(ctypes.c_int64)];self.lib.replay_poll.restype=ctypes.c_uint64
        self.lib.replay_reset()
    def send(self,event):self.lib.replay_send(event['handle'],(ctypes.c_uint32*4)(*event['raw']),BASE+event['t'])
    def poll(self,time):
        out=(ctypes.c_int64*60)();self.lib.replay_poll(BASE+time,out);return list(out)

def event(ms,amp=1,low=10,high=20,handle=3):
    return dict(t=round(ms*NS),handle=handle,raw=[bits(amp),bits(low),bits(amp),bits(high)],valid=True)
def sequence(events):
    counters={};out=[]
    for row in sorted(events,key=lambda r:r['t']):
        e=dict(row);h=e['handle'];counters[h]=counters.get(h,0)+1
        e['seq']=e.get('seq',counters[h]);out.append(e)
    return out
def scenarios():
    ratios=[1,1,1,.85,.65,.45,.25,.15,.05]
    descending=[event(i*16,1,10*r,20*r) for i,r in enumerate(ratios)]+[event(144,0,0,0)]
    frames=[]
    for i,r in enumerate(ratios):frames.extend([event(i*16,1,10*r,20*r),event(i*16+.001,0,0,0)])
    nonmonotonic=[event(i*16,1,10*r,20*r) for i,r in enumerate([1,.85,.65,.45,.75,.6])]+[event(100,0,0,0)]
    # Drop an actual delivered sample; the next observed sequence exposes the hole.
    missing=[r for i,r in enumerate(sequence(descending)) if i!=5]
    dual=descending+[event(i*16,1,0,60,0x10003) for i in range(10)]+[event(160,0,0,0,0x10003)]
    shifted=frames+[{**e,'t':e['t']+1000,'handle':0x10003} for e in frames]
    return {
        'constant':sequence([event(i*16,1,7.5,12) for i in range(9)]+[event(144,0,0,0)]),
        'constant-one-band':sequence([event(i*16,1,0,60) for i in range(9)]+[event(144,0,0,0)]),
        'descending':sequence(descending),'frame-clear-rewrite':sequence(frames),
        'nonmonotonic':sequence(nonmonotonic),'missing':missing,'dual-one-constant':sequence(dual),
        'fast-rebound':sequence([event(0),event(1,1,8,16),event(2,1,9,18),event(16,1,6,12),event(32,1,4,8),event(50,0,0,0)]),
        'nearzero-rebound':sequence([event(i*16,1,10*r,20*r) for i,r in enumerate([1,.85,.65,.001,.9])]+[event(100,0,0,0)]),
        'dual-shifted':sequence(shifted),
        'weak-short-pulse':sequence([event(5,.125),event(5.001,0,0,0)]),
        'short-pulse':sequence([event(5),event(5.001,0,0,0)]),
        'timeout':sequence([event(0)]),
        'amplitude-change':sequence(descending[:5]+[event(80,.5,3,6),event(120,0,0,0)]),
        'unknown-shape':sequence([event(i*16,1,10*r,20*s) for i,(r,s) in enumerate([(1,1),(.8,.95),(.6,.9),(.4,.85)])]+[event(80,0,0,0)]),
    }

def execute(events,simulators,phase=0,order='send-first',end=400*NS,native_path=None,control=None):
    native=Native(native_path);index=0
    for time in range(phase,end+1,20*NS):
        while index<len(events) and (events[index]['t']<time or (events[index]['t']==time and order=='send-first')):
            e=events[index];native.send(e)
            for sim in simulators:sim.feed(BASE+e['t'],e['handle'],e['seq'],e['raw'],e.get('valid',True))
            index+=1
        raw=native.poll(time)
        if control:control(time,raw,simulators)
        for sim in simulators:sim.poll(BASE+time,raw)
