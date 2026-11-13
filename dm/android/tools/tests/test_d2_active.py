"""Targeted Active tests and six saved envelopes. No APK/device action."""
import argparse
import csv
import json
import subprocess
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from d2_causal_model import Simulator,NS
from d2_replay_support import Native,BASE
p=argparse.ArgumentParser();p.add_argument('--jdk',type=Path,required=True);p.add_argument('--replay-lib',type=Path);p.add_argument('--capture',type=Path);p.add_argument('--output',type=Path,default=Path(__file__).resolve().parents[2]/'app/build/d2-tests');a=p.parse_args()
root=Path(__file__).resolve().parents[4];a.output.mkdir(parents=True,exist_ok=True)
main=root/'dm/android/app/src/main/java/com/xiaoboluo/d2r';debug=root/'dm/android/app/src/debug/java/com/xiaoboluo/d2r';classes=a.output/'classes';classes.mkdir(exist_ok=True)
files=[main/n for n in ('RumbleOutputCore.java','RumbleDOutputCore.java','RumbleDCalibration.java','RumbleDRaw.java','RumbleDStream.java','RumbleCausalModel.java','RumbleDActive.java','RumbleDSession.java')]
files += [Path(__file__).with_name(n) for n in ('RumbleDActiveTest.java','RumbleDActiveReplay.java')]
subprocess.run([str(a.jdk/'bin/javac.exe'),'--release','17','-encoding','UTF-8','-d',str(classes),*map(str,files)],check=True)
for name in ('RumbleDActiveTest',):
 subprocess.run([str(a.jdk/'bin/java.exe'),'-cp',str(classes),'com.xiaoboluo.d2r.'+name],check=True)
if a.capture is None:
 print('PASS standalone synthetic core regression; SKIP optional historical integration (no --capture)')
 raise SystemExit(0)
required = ['analysis-raw-events.jsonl','analysis-envelopes.csv','d2-shadow-20261112-164857-live.log']
if not all((a.capture/name).is_file() for name in required) or a.replay_lib is None or not a.replay_lib.is_file():
 print('SKIP optional historical integration: capture files and --replay-lib required')
 raise SystemExit(0)
raw=[json.loads(line) for line in (a.capture/'analysis-raw-events.jsonl').read_text().splitlines()]
envelopes=list(csv.DictReader((a.capture/'analysis-envelopes.csv').open()))
selected=[]
for phase,limit in (('CONFIRMED',3),('FALLBACK',2),('BASELINE',1)):
 selected += [e for e in envelopes if e['handle']=='3' and e['phase']==phase][:limit]
log=(a.capture/'d2-shadow-20261112-164857-live.log').read_text(encoding='utf-8')
import re
ticks=sorted({int(re.search(r'tick_start_ns=(\d+)',line).group(1)) for line in log.splitlines() if 'RDT D ' in line})
class Sink:
 def append(self,value):pass
results=[]
for i,env in enumerate(selected,1001):
 start=int(env['onset_ns'])-20*NS;end=int(env['final_zero_ns'])+180*NS
 events=[e for e in raw if int(env['epoch'])==e['epoch'] and start<=e['ns']<=end]
 clock=[t+500000 for t in ticks if start<=t<=end]
 clock=sorted(set(clock+[start,end]))
 native=Native(a.replay_lib);sim=Simulator();sim.timeline=Sink()
 for c in sim.channels.values():c.diagnostics=Sink()
 protocol=['S,'+str(i)];index=0;delivered=0;local={}
 for now in clock:
  current=[]
  while index<len(events) and events[index]['ns']<=now:
   e=events[index];h=e['handle'];local[h]=local.get(h,0)+1
   native.send(dict(t=e['ns']-BASE,handle=h,raw=e['raw']));sim.feed(e['ns'],h,local[h],e['raw']);current.append(e);index+=1
  frame=native.poll(now-BASE);frame[2]=i;sim.poll(now,frame)
  assert len(current)<=128
  b=[1,i,1,1,index,0,delivered,len(current),0,now,len(current),0,4096,10,delivered+1 if current else 0,20,index,0,0,0]
  for number,e in enumerate(current,delivered+1):b.extend([i,1,number,e['ns'],e['handle'],31,*e['raw']])
  delivered=index;protocol.append(','.join(map(str,['T',now,1,index,*frame,*b])))
 input_text='\n'.join(protocol)+'\n'
 (a.output/('case-'+str(i)+'.txt')).write_text(input_text)
 result=subprocess.run([str(a.jdk/'bin/java.exe'),'-cp',str(classes),'com.xiaoboluo.d2r.RumbleDActiveReplay'],input=input_text.encode(),capture_output=True,check=True)
 actual=[line.decode().split(',') for line in result.stdout.splitlines()]
 expected=[[str(v) for v in event] for event in sim.output.events]
 assert actual==expected,(i,actual,expected)
 record=dict(envelope=env['envelope'],shape=env['phase'],polls=len(clock),source_events=len(events),exact=True)
 results.append(record);print('ACTIVE SAVED INPUT PASS',record,flush=True)
(a.output/'results.json').write_text(json.dumps(dict(passed=True,selected_envelopes=results,full_history_rerun=False),indent=2))
