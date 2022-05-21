
import javafx.scene.control.TableRow;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.TrustAnchor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

enum PacketType{
    DATAPKG((byte)0),   //数据包
    PROBEPKG((byte)1),  //探测包
    ACKPKG((byte)2),    //ACK报文
    NAKPKG((byte)3),   //NAK报文
    RETRANS((byte)4),
    FIN((byte)5),
    PTO((byte)6);
    public byte t;

    PacketType(byte t) {
        this.t= t;
    }
}
/*
 * SPLB头部
 * */
class SplbHdr{
    long timeStamp = 0L;
    int probeSeq;   //探测序号
    int pathSeq;    //路径序号
    int dataSeq;    //数据序号
    PacketType type;
    byte pathNum;   //子路径编码



    SplbHdr(PacketType t,byte pathNum,int probeSeq,int pathSeq,int dataSeq){
        this.type = t;
        this.pathNum = pathNum;
        this.probeSeq = probeSeq;
        this.pathSeq = pathSeq;
        this.dataSeq = dataSeq;
    }
    SplbHdr(byte[] hdr){
        ByteBuffer byteBuffer = ByteBuffer.wrap(hdr);
        this.timeStamp = byteBuffer.getLong();
        this.probeSeq = byteBuffer.getInt();
        this.pathSeq = byteBuffer.getInt();
        this.dataSeq = byteBuffer.getInt();
        byte t = byteBuffer.get();
        switch (t){
            case (byte)0:
                this.type = PacketType.DATAPKG;break;
            case (byte)1:
                this.type = PacketType.PROBEPKG;break;
            case (byte)2:
                this.type = PacketType.ACKPKG;break;
            case (byte)3:
                this.type = PacketType.NAKPKG;break;
            case (byte)4:
                this.type = PacketType.RETRANS;break;
            case (byte)5:
                this.type = PacketType.FIN;break;
            case (byte)6:
                this.type = PacketType.PTO;break;
        }
        this.pathNum = byteBuffer.get();

    }
    public byte[] toByteArray() {
        ByteBuffer buf = ByteBuffer.allocate(22);
        buf.putLong(this.timeStamp);
        buf.putInt(this.probeSeq);
        buf.putInt(this.pathSeq);
        buf.putInt(this.dataSeq);
        buf.put(this.type.t);
        buf.put(this.pathNum);
        return buf.array();
    }

}
class SplbData implements Comparable<SplbData>{
    SplbHdr hdr;
    byte[] data;
    int len;
    SplbData(SplbHdr hdr,byte[] data,int len){
        this.hdr = hdr;
        this.data = data;
        this.len = len;
    }
    @Override
    public int compareTo(SplbData o) {
        return hdr.pathSeq - o.hdr.pathSeq ;
    }
}



class LTEControlBlock {
    public DatagramSocket socket = null;
    InetAddress dstIP = null;
    public int dstPort = 0;
    public int recvBytes = 0;
    public int nowRecv = 0;
    public long nowTime = 0;
    public int sgap = 0;
    public boolean endSign = false;
    public int wantedSeq = 1;
    public int arrInOrderCounter = 0;
    public int ackThres = 3;
    public long startTime = 0;
    public int recvCounter = 0;
    public long recvCounterTS = 0;
    public PriorityBlockingQueue<SplbData> inorderQueue;
    public PriorityBlockingQueue<SplbData> outorderQueue;
    public ExecutorService lteProbeExecutor = null;
    public ExecutorService lteDataExecutor = null;
    public ExecutorService lteAckExecutor = null;
    LTEControlBlock(DatagramSocket socket){
        this.socket = socket;
        this.inorderQueue = new PriorityBlockingQueue<SplbData>();
        this.outorderQueue = new PriorityBlockingQueue<SplbData>();
        this.lteProbeExecutor = Executors.newSingleThreadExecutor();
        this.lteDataExecutor = Executors.newSingleThreadExecutor();
        this.lteAckExecutor = Executors.newSingleThreadExecutor();
    }
}
class LTEProbeTask implements Runnable{

    LTEControlBlock lteControlBlock = null;
    SplbHdr hdr;
    LTEProbeTask(LTEControlBlock lteControlBlock,SplbHdr hdr){
        this.lteControlBlock = lteControlBlock;
        this.hdr = hdr;
    }
    @Override
    public void run() {
        try {
            hdr.dataSeq = lteControlBlock.sgap;
            byte[] probe = hdr.toByteArray();
            DatagramPacket packet = new DatagramPacket(probe,probe.length,lteControlBlock.dstIP,lteControlBlock.dstPort);
            lteControlBlock.socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
class LTEACKTask implements Runnable{
    LTEControlBlock lteControlBlock = null;
    int ackSeq = 0;
    int wantedSeq = 0;
    PacketType type;
    long timeStamp;
    LTEACKTask(LTEControlBlock lteControlBlock,int ackSeq,int wantedSeq,PacketType type,long timeStamp){
        this.lteControlBlock = lteControlBlock;
        this.ackSeq = ackSeq;
        this.wantedSeq = wantedSeq;
        this.type = type;
        this.timeStamp = timeStamp;
    }

    @Override
    public void run() {
        SplbHdr ackHdr;
        if(this.type == PacketType.ACKPKG){
            ackHdr = new SplbHdr(PacketType.ACKPKG, (byte) 1, 0, this.ackSeq, this.wantedSeq);
        }else{
            ackHdr = new SplbHdr(PacketType.NAKPKG, (byte) 1, 0, this.ackSeq,this.wantedSeq);
        }
        ackHdr.timeStamp = timeStamp;
        byte[] ackMsg = ackHdr.toByteArray();
        DatagramPacket ackPkt = new DatagramPacket(ackMsg,ackMsg.length,lteControlBlock.dstIP,lteControlBlock.dstPort);
        try {
            lteControlBlock.socket.send(ackPkt);
        } catch (IOException e) {
            // e.printStackTrace();
        }
    }
}
class LTEDataTask implements Runnable{

    LTEControlBlock lteControlBlock = null;
    SplbHdr hdr = null;
    byte[] msg;
    int len;
    LTEDataTask(LTEControlBlock lteControlBlock,SplbHdr hdr,byte[] msg,int len){
        this.lteControlBlock = lteControlBlock;
        this.hdr = hdr;
        this.msg = msg;
        this.len = len;
    }
    @Override
    public void run() {
        lteControlBlock.recvBytes += msg.length;
        if(hdr.type == PacketType.DATAPKG){
            if(hdr.pathSeq == lteControlBlock.wantedSeq){   //按序到达的数据包
                lteControlBlock.inorderQueue.put(new SplbData(hdr,msg,len));
                lteControlBlock.arrInOrderCounter++;
                lteControlBlock.wantedSeq++;
                if(lteControlBlock.arrInOrderCounter == lteControlBlock.ackThres){
                    lteControlBlock.arrInOrderCounter = 0;
                    //  System.out.println("ack:"+hdr.pathSeq + "," + lteControlBlock.wantedSeq);
                    LTEACKTask ackTask = new LTEACKTask(lteControlBlock,hdr.pathSeq,lteControlBlock.wantedSeq,PacketType.ACKPKG,hdr.timeStamp);
                    lteControlBlock.lteAckExecutor.execute(ackTask);
                }

                while(lteControlBlock.outorderQueue.size() > 0){
                    if(lteControlBlock.outorderQueue.peek().hdr.pathSeq < lteControlBlock.wantedSeq){  //检查是否可以从无序队列迁移出来
                        lteControlBlock.outorderQueue.poll();
                    } else if(lteControlBlock.outorderQueue.peek().hdr.pathSeq == lteControlBlock.wantedSeq){
                        SplbData fData = lteControlBlock.outorderQueue.poll();
                        if(fData.hdr.pathSeq == lteControlBlock.wantedSeq){
                            lteControlBlock.wantedSeq++;
                            lteControlBlock.inorderQueue.put(fData);
                        }
                    }else{
                        break;
                    }
                }
            }
            else if(hdr.pathSeq > lteControlBlock.wantedSeq){  //乱序到达的数据包
                lteControlBlock.arrInOrderCounter = 0;
                lteControlBlock.outorderQueue.put(new SplbData(hdr,msg,len));
                // System.out.println("nak:"+hdr.pathSeq + "," + lteControlBlock.wantedSeq);
                LTEACKTask nakTask = new LTEACKTask(lteControlBlock,hdr.pathSeq,lteControlBlock.wantedSeq,PacketType.NAKPKG,hdr.timeStamp);//对乱序数据包立即进行确认。
                lteControlBlock.lteAckExecutor.execute(nakTask);
            }
        }else if(hdr.type == PacketType.RETRANS){  //每个重传数据包都需要进行确认
            if(hdr.pathSeq < lteControlBlock.wantedSeq){ //这个不需要确认
                return;
            }else if(hdr.pathSeq == lteControlBlock.wantedSeq){   //按序到达的数据包
                lteControlBlock.inorderQueue.put(new SplbData(hdr,msg,len));
                lteControlBlock.wantedSeq++;
                LTEACKTask ackTask = new LTEACKTask(lteControlBlock,hdr.pathSeq,lteControlBlock.wantedSeq,PacketType.ACKPKG,hdr.timeStamp);
                lteControlBlock.lteAckExecutor.execute(ackTask);
                while(lteControlBlock.outorderQueue.size() > 0){    //检查是否可以从无序队列迁移出来
                    if(lteControlBlock.outorderQueue.peek().hdr.pathSeq < lteControlBlock.wantedSeq){
                        lteControlBlock.outorderQueue.poll();
                    } else if(lteControlBlock.outorderQueue.peek().hdr.pathSeq == lteControlBlock.wantedSeq){
                        SplbData fData = lteControlBlock.outorderQueue.poll();
                        if(fData.hdr.pathSeq == lteControlBlock.wantedSeq){
                            lteControlBlock.inorderQueue.put(fData);
                            lteControlBlock.wantedSeq++;
                        }
                    }else{
                        break;
                    }
                }
            }else{
                LTEACKTask nakTask = new LTEACKTask(lteControlBlock,hdr.pathSeq,lteControlBlock.wantedSeq,PacketType.NAKPKG,hdr.timeStamp);
                lteControlBlock.lteAckExecutor.execute(nakTask);
                lteControlBlock.outorderQueue.put(new SplbData(hdr,msg,len));
            }
        }else if(hdr.type == PacketType.FIN){
            if(hdr.pathSeq == lteControlBlock.wantedSeq){   //按序到达的数据包
                LTEACKTask ackTask = new LTEACKTask(lteControlBlock,hdr.pathSeq,lteControlBlock.wantedSeq,PacketType.FIN,hdr.timeStamp);
                lteControlBlock.lteAckExecutor.execute(ackTask);
                lteControlBlock.endSign = true;
            }else if(hdr.pathSeq < lteControlBlock.wantedSeq){
                LTEACKTask ackTask = new LTEACKTask(lteControlBlock,hdr.pathSeq,lteControlBlock.wantedSeq,PacketType.NAKPKG,hdr.timeStamp);
                lteControlBlock.lteAckExecutor.execute(ackTask);
            }
        }
        if(lteControlBlock.startTime ==0 ){
            lteControlBlock.startTime = System.nanoTime();
        }

    }
}

class LTETask implements Runnable {
    LTEControlBlock lteControlBlock;
    LTETask(LTEControlBlock lteControlBlock){
        this.lteControlBlock = lteControlBlock;
    }
    @Override
    public void run() {
        while (!lteControlBlock.endSign){
            byte[] data = new byte[534];
            DatagramPacket packet = new DatagramPacket(data,data.length);
            try {
                lteControlBlock.socket.receive(packet);
                long ts = System.nanoTime();
                if(lteControlBlock.dstIP == null){
                    InetAddress srcIP = packet.getAddress();
                    int srcPort = packet.getPort();
                    lteControlBlock.dstIP = srcIP;
                    lteControlBlock.dstPort = srcPort;
                }
                byte[] msg = packet.getData();
                int len = packet.getLength();
                SplbHdr hdr = new SplbHdr(msg);
                if(hdr.type == PacketType.PROBEPKG){
                    LTEProbeTask probeTask = new LTEProbeTask(lteControlBlock,hdr);
                    lteControlBlock.lteProbeExecutor.execute(probeTask);
                }else{
                    //  System.out.println(hdr.type + ","+hdr.dataSeq + ":" + msg[22] + ":"+ msg[len-1]);
                    lteControlBlock.nowRecv += 1;
                    lteControlBlock.recvCounter += 1;
                    if(lteControlBlock.recvCounter == 1){
                        lteControlBlock.recvCounterTS = ts;
                    } else if(lteControlBlock.recvCounter==200){
                        lteControlBlock.recvCounter=0;
                        int inteval = (int)((ts - lteControlBlock.recvCounterTS)/1000);
                        int gap = inteval/200;
                        if(lteControlBlock.sgap ==0 ){
                            lteControlBlock.sgap = gap;
                        }else{
                            lteControlBlock.sgap = (int) (lteControlBlock.sgap* 0.8 + gap*0.2);
                        }
                        // System.out.println("gap:"+gap+",sagp:"+ lteControlBlock.sgap);
                    }
                    LTEDataTask dataTask = new LTEDataTask(lteControlBlock,hdr,msg,len);
                    lteControlBlock.lteDataExecutor.execute(dataTask);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

class WiFiControlBlock {
    public DatagramSocket socket = null;
    InetAddress dstIP = null;
    public int dstPort = 0;
    public int recvBytes = 0;
    public int nowRecv = 0;
    public long nowTime = 0;
    public int sgap = 0;
    public boolean endSign = false;
    public int wantedSeq = 1;
    public int arrInOrderCounter = 0;
    public int ackThres = 3;
    public long startTime = 0;
    public int recvCounter = 0;
    public long recvCounterTS = 0;
    public PriorityBlockingQueue<SplbData> inorderQueue;
    public PriorityBlockingQueue<SplbData> outorderQueue;
    public ExecutorService wifiProbeExecutor = null;
    public ExecutorService wifiDataExecutor = null;
    public ExecutorService wifiAckExecutor = null;
    WiFiControlBlock(DatagramSocket socket){
        this.socket = socket;
        this.inorderQueue = new PriorityBlockingQueue<SplbData>();
        this.outorderQueue = new PriorityBlockingQueue<SplbData>();
        this.wifiProbeExecutor = Executors.newSingleThreadExecutor();
        this.wifiDataExecutor = Executors.newSingleThreadExecutor();
        this.wifiAckExecutor = Executors.newSingleThreadExecutor();
    }
}
class WiFiProbeTask implements Runnable{

    WiFiControlBlock wifiControlBlock = null;
    SplbHdr hdr;
    WiFiProbeTask(WiFiControlBlock wifiControlBlock,SplbHdr hdr){
        this.wifiControlBlock = wifiControlBlock;
        this.hdr = hdr;
    }
    @Override
    public void run() {
        try {
            hdr.dataSeq = wifiControlBlock.sgap;
            byte[] probe = hdr.toByteArray();
            DatagramPacket packet = new DatagramPacket(probe,probe.length,wifiControlBlock.dstIP,wifiControlBlock.dstPort);
            wifiControlBlock.socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
class WiFiACKTask implements Runnable{
    WiFiControlBlock wifiControlBlock = null;
    int ackSeq = 0;
    int wantedSeq = 0;
    PacketType type;
    long timeStamp;
    WiFiACKTask(WiFiControlBlock wifiControlBlock,int ackSeq,int wantedSeq,PacketType type,long timeStamp){
        this.wifiControlBlock = wifiControlBlock;
        this.ackSeq = ackSeq;
        this.wantedSeq = wantedSeq;
        this.type = type;
        this.timeStamp = timeStamp;
    }

    @Override
    public void run() {
        SplbHdr ackHdr;
        if(this.type == PacketType.ACKPKG){
            ackHdr = new SplbHdr(PacketType.ACKPKG, (byte) 1, 0, this.ackSeq, this.wantedSeq);
        }else{
            ackHdr = new SplbHdr(PacketType.NAKPKG, (byte) 1, 0, this.ackSeq,this.wantedSeq);
        }
        ackHdr.timeStamp = timeStamp;
        byte[] ackMsg = ackHdr.toByteArray();
        DatagramPacket ackPkt = new DatagramPacket(ackMsg,ackMsg.length,wifiControlBlock.dstIP,wifiControlBlock.dstPort);
        try {
            wifiControlBlock.socket.send(ackPkt);
        } catch (IOException e) {
            // e.printStackTrace();
        }
    }
}
class WiFiDataTask implements Runnable{

    WiFiControlBlock wifiControlBlock = null;
    SplbHdr hdr = null;
    byte[] msg;
    int len;
    WiFiDataTask(WiFiControlBlock wifiControlBlock,SplbHdr hdr,byte[] msg,int len){
        this.wifiControlBlock = wifiControlBlock;
        this.hdr = hdr;
        this.msg = msg;
        this.len = len;
    }
    @Override
    public void run() {
        wifiControlBlock.recvBytes += msg.length;
        if(hdr.type == PacketType.DATAPKG){
            if(hdr.pathSeq == wifiControlBlock.wantedSeq){   //按序到达的数据包
                wifiControlBlock.inorderQueue.put(new SplbData(hdr,msg,len));
                wifiControlBlock.arrInOrderCounter++;
                wifiControlBlock.wantedSeq++;
                if(wifiControlBlock.arrInOrderCounter == wifiControlBlock.ackThres){
                    wifiControlBlock.arrInOrderCounter = 0;
                  //  System.out.println("ack:"+hdr.pathSeq + "," + wifiControlBlock.wantedSeq);
                    WiFiACKTask ackTask = new WiFiACKTask(wifiControlBlock,hdr.pathSeq,wifiControlBlock.wantedSeq,PacketType.ACKPKG,hdr.timeStamp);
                    wifiControlBlock.wifiAckExecutor.execute(ackTask);
                }

                while(wifiControlBlock.outorderQueue.size() > 0){
                    if(wifiControlBlock.outorderQueue.peek().hdr.pathSeq < wifiControlBlock.wantedSeq){  //检查是否可以从无序队列迁移出来
                        wifiControlBlock.outorderQueue.poll();
                    } else if(wifiControlBlock.outorderQueue.peek().hdr.pathSeq == wifiControlBlock.wantedSeq){
                        SplbData fData = wifiControlBlock.outorderQueue.poll();
                        if(fData.hdr.pathSeq == wifiControlBlock.wantedSeq){
                            wifiControlBlock.wantedSeq++;
                            wifiControlBlock.inorderQueue.put(fData);
                        }
                    }else{
                        break;
                    }
                }
            }
            else if(hdr.pathSeq > wifiControlBlock.wantedSeq){  //乱序到达的数据包
                wifiControlBlock.arrInOrderCounter = 0;
                wifiControlBlock.outorderQueue.put(new SplbData(hdr,msg,len));
               // System.out.println("nak:"+hdr.pathSeq + "," + wifiControlBlock.wantedSeq);
                WiFiACKTask nakTask = new WiFiACKTask(wifiControlBlock,hdr.pathSeq,wifiControlBlock.wantedSeq,PacketType.NAKPKG,hdr.timeStamp);//对乱序数据包立即进行确认。
                wifiControlBlock.wifiAckExecutor.execute(nakTask);
            }
        }else if(hdr.type == PacketType.RETRANS){  //每个重传数据包都需要进行确认
            if(hdr.pathSeq < wifiControlBlock.wantedSeq){ //这个不需要确认
                return;
            }else if(hdr.pathSeq == wifiControlBlock.wantedSeq){   //按序到达的数据包
                wifiControlBlock.inorderQueue.put(new SplbData(hdr,msg,len));
                wifiControlBlock.wantedSeq++;
                WiFiACKTask ackTask = new WiFiACKTask(wifiControlBlock,hdr.pathSeq,wifiControlBlock.wantedSeq,PacketType.ACKPKG,hdr.timeStamp);
                wifiControlBlock.wifiAckExecutor.execute(ackTask);
                while(wifiControlBlock.outorderQueue.size() > 0){    //检查是否可以从无序队列迁移出来
                    if(wifiControlBlock.outorderQueue.peek().hdr.pathSeq < wifiControlBlock.wantedSeq){
                        wifiControlBlock.outorderQueue.poll();
                    } else if(wifiControlBlock.outorderQueue.peek().hdr.pathSeq == wifiControlBlock.wantedSeq){
                        SplbData fData = wifiControlBlock.outorderQueue.poll();
                        if(fData.hdr.pathSeq == wifiControlBlock.wantedSeq){
                            wifiControlBlock.inorderQueue.put(fData);
                            wifiControlBlock.wantedSeq++;
                        }
                    }else{
                        break;
                    }
                }
            }else{
                WiFiACKTask nakTask = new WiFiACKTask(wifiControlBlock,hdr.pathSeq,wifiControlBlock.wantedSeq,PacketType.NAKPKG,hdr.timeStamp);
                wifiControlBlock.wifiAckExecutor.execute(nakTask);
                wifiControlBlock.outorderQueue.put(new SplbData(hdr,msg,len));
            }
        }else if(hdr.type == PacketType.FIN){
            if(hdr.pathSeq == wifiControlBlock.wantedSeq){   //按序到达的数据包
                WiFiACKTask ackTask = new WiFiACKTask(wifiControlBlock,hdr.pathSeq,wifiControlBlock.wantedSeq,PacketType.FIN,hdr.timeStamp);
                wifiControlBlock.wifiAckExecutor.execute(ackTask);
                wifiControlBlock.endSign = true;
            }else if(hdr.pathSeq < wifiControlBlock.wantedSeq){
                WiFiACKTask ackTask = new WiFiACKTask(wifiControlBlock,hdr.pathSeq,wifiControlBlock.wantedSeq,PacketType.NAKPKG,hdr.timeStamp);
                wifiControlBlock.wifiAckExecutor.execute(ackTask);
            }
        }
        if(wifiControlBlock.startTime ==0 ){
            wifiControlBlock.startTime = System.nanoTime();
        }

    }
}

class WiFiTask implements Runnable {
    WiFiControlBlock wifiControlBlock;
    WiFiTask(WiFiControlBlock wifiControlBlock){
        this.wifiControlBlock = wifiControlBlock;
    }
    @Override
    public void run() {
        while (!wifiControlBlock.endSign){
            byte[] data = new byte[534];
            DatagramPacket packet = new DatagramPacket(data,data.length);
            try {
                wifiControlBlock.socket.receive(packet);
                long ts = System.nanoTime();
                if(wifiControlBlock.dstIP == null){
                    InetAddress srcIP = packet.getAddress();
                    int srcPort = packet.getPort();
                    wifiControlBlock.dstIP = srcIP;
                    wifiControlBlock.dstPort = srcPort;
                }
                byte[] msg = packet.getData();
                int len = packet.getLength();
                SplbHdr hdr = new SplbHdr(msg);
                if(hdr.type == PacketType.PROBEPKG){
                    WiFiProbeTask probeTask = new WiFiProbeTask(wifiControlBlock,hdr);
                    wifiControlBlock.wifiProbeExecutor.execute(probeTask);
                }else{
                  //  System.out.println(hdr.type + ","+hdr.dataSeq + ":" + msg[22] + ":"+ msg[len-1]);
                    if(hdr.type != PacketType.FIN){
                        wifiControlBlock.nowRecv += 1;
                        wifiControlBlock.recvCounter += 1;
                    }
                    if(wifiControlBlock.recvCounter == 1){
                        wifiControlBlock.recvCounterTS = ts;
                    } else if(wifiControlBlock.recvCounter==200){
                        wifiControlBlock.recvCounter=0;
                        int inteval = (int)((ts - wifiControlBlock.recvCounterTS)/1000);
                        int gap = inteval/200;
                        if(wifiControlBlock.sgap ==0 ){
                            wifiControlBlock.sgap = gap;
                        }else{
                            wifiControlBlock.sgap = (int) (wifiControlBlock.sgap* 0.8 + gap*0.2);
                        }
                       // System.out.println("gap:"+gap+",sagp:"+ wifiControlBlock.sgap);
                    }
                    WiFiDataTask dataTask = new WiFiDataTask(wifiControlBlock,hdr,msg,len);
                    wifiControlBlock.wifiDataExecutor.execute(dataTask);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
class SpeedTask implements Runnable{
    public DatagramSocket speedSocket;
    public WiFiControlBlock wifiControlBlock;
    public LTEControlBlock lteControlBlock;

    SpeedTask(WiFiControlBlock wifiControlBlock,LTEControlBlock lteControlBlock){
        this.wifiControlBlock = wifiControlBlock;
        this.lteControlBlock = lteControlBlock;
    }

    @Override
    public void run() {
        int wifiLastRecv = 0;
        int lteLastRecv = 0;
        long lastTime = 0;
        try {
            InetAddress address = InetAddress.getByName("127.0.0.1");
            int port = 15000;
            speedSocket = new DatagramSocket(12345);
            while (true){
                if(lastTime == 0){
                    lastTime = System.nanoTime();
                    TimeUnit.SECONDS.sleep(1);
                }else{
                    long curTime = System.nanoTime();
                    long interval = (curTime - lastTime) / 1000;
                    lastTime = curTime;
                    int wifiRecv = wifiControlBlock.recvBytes - wifiLastRecv;
                    int lteRecv = lteControlBlock.recvBytes - lteLastRecv;
                    wifiLastRecv = wifiControlBlock.recvBytes;
                    lteLastRecv = lteControlBlock.recvBytes;
                    double wifiBW =(wifiRecv * 8.0) / interval;
                    double lteBW = (lteRecv * 8.0) / interval;
                    int wseq = wifiControlBlock.wantedSeq;
                    int lseq = lteControlBlock.wantedSeq;
                    String speed = lteBW+"-"+wifiBW;
                    byte[] data = speed.getBytes();
                    int len = data.length;
                    DatagramPacket packet = new DatagramPacket(data,len,address,port);
                    speedSocket.send(packet);
                    System.out.println("lte:"+lteBW+"     :wifi:"+wifiBW+"      :lte-seq:"+lseq+"    :wifi-seq:"+wseq);
                    TimeUnit.MILLISECONDS.sleep(1000);
                }
            }
        }catch (InterruptedException e) {
            e.printStackTrace();
        } catch (SocketException e) {
            e.printStackTrace();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
class APPTask implements Runnable{
    public WiFiControlBlock wifiControlBlock;
    public LTEControlBlock lteControlBlock;

    APPTask(WiFiControlBlock wifiControlBlock,LTEControlBlock lteControlBlock){
        this.wifiControlBlock = wifiControlBlock;
        this.lteControlBlock = lteControlBlock;
    }

    @Override
    public void run() {
        int wantedSeq = 1;
        PriorityBlockingQueue<SplbData> wq= wifiControlBlock.inorderQueue;
        PriorityBlockingQueue<SplbData> lq = lteControlBlock.inorderQueue;
        File file = new File("/home/test.mkv");
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        int counter = 0;
        while (true){
            if(wq.size()>0){
                if(wq.peek().hdr.dataSeq < wantedSeq){
                    wq.poll();

                }else if(wq.peek().hdr.dataSeq == wantedSeq){
                    SplbData poll = wq.poll();
                    try {
                        //System.out.println(wantedSeq + ","+poll.hdr.dataSeq + ":" + poll.data[22] + ":"+ poll.data[poll.len-1]);
                        os.write(poll.data,22,poll.len-22);
                        os.flush();

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    wantedSeq++;
                }
            }
            if(lq.size()>0){
                if(lq.peek().hdr.dataSeq < wantedSeq){
                    lq.poll();
                }else if(lq.peek().hdr.dataSeq == wantedSeq){
                    SplbData poll = lq.poll();
                    try {
                        os.write(poll.data,22,poll.len-22);
                        //System.out.println(wantedSeq + ","+poll.hdr.dataSeq + ":" + poll.data[22] + ":"+ poll.data[poll.len-1]);
                        os.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    wantedSeq++;
                }
            }
        }
    }
}
class ServerSock {
    public int port = 18000;
    public DatagramSocket lteSocket;
    public DatagramSocket wifiSocket;
    public Thread lteRecvThread = null;
    public Thread wifiRecvThread = null;
    public Thread consumeDataThread = null;
    public Thread speedThread = null;
    public void init(){
        try {
            this.lteSocket = new DatagramSocket(this.port);
            this.wifiSocket = new DatagramSocket(this.port+1);

        } catch (SocketException e) {
            e.printStackTrace();
        }
        LTEControlBlock lteControlBlock = new LTEControlBlock(lteSocket);
        WiFiControlBlock wifiControlBlock = new WiFiControlBlock(wifiSocket);
        LTETask lteTask = new LTETask(lteControlBlock);
        WiFiTask wifiTask = new WiFiTask(wifiControlBlock);
        this.lteRecvThread = new Thread(lteTask);
        this.wifiRecvThread = new Thread(wifiTask);
        this.speedThread = new Thread(new SpeedTask(wifiControlBlock,lteControlBlock));
        this.consumeDataThread = new Thread(new APPTask(wifiControlBlock,lteControlBlock));
    }
    public void run(){
        this.init();
        this.speedThread.start();
        this.lteRecvThread.start();
        this.wifiRecvThread.start();
        this.consumeDataThread.start();
    }
}
public class Server{
    public static void main(String[] args) {
        ServerSock serverSock = new ServerSock();
        serverSock.run();
    }
}