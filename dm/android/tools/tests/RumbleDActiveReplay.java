package com.xiaoboluo.d2r;
import java.io.BufferedReader;
import java.io.InputStreamReader;
/** Targeted saved-input replay. Physical calls are fake and are counted once. */
public final class RumbleDActiveReplay {
    public static void main(String[] args) throws Exception {
        BufferedReader in=new BufferedReader(new InputStreamReader(System.in));String line;
        long[] time={0};RumbleDActive active=null;RumbleDOutputCore baseline=null;long owner=0;
        RumbleOutputCore.Motor fake=new RumbleOutputCore.Motor() {
            public void vibrate(int duration,int amp){System.out.println(time[0]/1000000+",vibrate,"+amp+","+duration);}
            public void cancel(){System.out.println(time[0]/1000000+",cancel,0,0");}
        };
        while((line=in.readLine())!=null) {
            String[] f=line.split(",");
            if(f[0].equals("S")) {
                owner=Long.parseLong(f[1]);active=new RumbleDActive(owner,()->time[0],null);baseline=new RumbleDOutputCore(new RumbleDCalibration());
            } else {
                time[0]=Long.parseLong(f[1]);long[] raw=new long[60],batch=new long[1300];
                for(int i=0;i<60;i++)raw[i]=Long.parseLong(f[i+4]);
                for(int i=64;i<f.length;i++)batch[i-64]=Long.parseLong(f[i]);
                long[] stamp={owner,Long.parseLong(f[2]),Long.parseLong(f[3]),raw[1]};
                active.prepare(stamp,batch,out->false);active.tick(time[0]/1000000,raw,baseline,fake);
            }
        }
    }
}
