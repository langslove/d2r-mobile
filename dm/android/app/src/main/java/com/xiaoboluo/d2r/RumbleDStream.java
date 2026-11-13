package com.xiaoboluo.d2r;

// Coverage observer only. Never supplies data to an output core or a motor.
final class RumbleDStream {
    static final int HEADER=20, ROW=10, BATCH=128, WORDS=HEADER+BATCH*ROW;
    private final long owner;
    private long epoch, next=1, lastTime, batches, events, failures, consumed;
    private boolean trustworthy, failedBeforeEpoch;
    RumbleDStream(long owner) { this.owner=owner; }
    void readFailed() { failures++;trustworthy=false;failedBeforeEpoch=epoch==0; }
    boolean accept(long[] data) {
        if (data==null || data.length!=WORDS || data[0]!=1 || data[1]!=owner || data[2]==0
                || data[10]<0 || data[10]>BATCH || data[12]!=4096 || data[13]!=ROW || data[15]!=HEADER) {
            readFailed();return false;
        }
        if (epoch!=data[2]) {
            if (epoch!=0 && Long.compareUnsigned(data[2],epoch)<=0) { readFailed();return false; }
            epoch=data[2];next=1;lastTime=0;consumed=0;
            trustworthy=data[6]==0 && !failedBeforeEpoch;
            // A failure with unknown epoch taints this first observed epoch. A later reset can recover.
            failedBeforeEpoch=false;
        }
        if (data[6]!=consumed || data[3]!=1 || data[5]!=0 || data[8]!=0
                || data[7]<0 || data[7]>4096 || data[4]!=data[6]+data[7]+data[5]) trustworthy=false;
        int count=(int)data[10];
        if ((count>0 && data[14]!=data[HEADER+2]) || data[7]<count) trustworthy=false;
        for (int i=0;i<count;i++) {
            int at=HEADER+i*ROW;
            long seq=data[at+2], time=data[at+3];
            if (data[at]!=owner || data[at+1]!=epoch || seq!=next || seq==0
                    || Long.compareUnsigned(time,lastTime)<0 || data[at+5]!=31) trustworthy=false;
            long handle=data[at+4];
            if (handle!=3 && handle!=0x10003 && handle!=0x2004 && handle!=0x12004) trustworthy=false;
            for (int j=0;j<4;j++) {
                long raw=data[at+6+j];
                float value=Float.intBitsToFloat((int)raw);
                if ((raw & ~0xffffffffL)!=0 || !Float.isFinite(value) || value<0
                        || ((j==0 || j==2) && value>1)) trustworthy=false;
            }
            next=seq+1;lastTime=time;
        }
        batches++;events+=count;consumed+=count;return true;
    }
    boolean trustworthy() { return trustworthy; }
    long epoch() { return epoch; }
    String summary() {
        return "D2T META owner="+Long.toUnsignedString(owner)+" epoch="+Long.toUnsignedString(epoch)
                +" trustworthy="+trustworthy+" fallback_d1="+!trustworthy+" batches="+batches
                +" events="+events+" read_failures="+failures;
    }
    // At most eight records per line, below Android log entry truncation limits.
    static String records(long[] data,int start,int count) {
        StringBuilder text=new StringBuilder(1800);
        text.append("D2T RAW owner=").append(Long.toUnsignedString(data[1]))
                .append(" epoch=").append(Long.toUnsignedString(data[2]));
        for (int i=start;i<start+count;i++) {
            int at=HEADER+i*ROW;
            text.append(" [seq=").append(Long.toUnsignedString(data[at+2]))
                    .append(" ns=").append(Long.toUnsignedString(data[at+3]))
                    .append(" h=").append(Long.toHexString(data[at+4]))
                    .append(" flags=").append(data[at+5]).append(" raw=");
            for(int j=0;j<4;j++) { if(j>0) text.append(',');text.append(Long.toHexString(data[at+6+j])); }
            text.append(']');
        }
        return text.toString();
    }
}
