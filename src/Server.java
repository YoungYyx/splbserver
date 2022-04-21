
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

enum PacketType{
    DATAPKG((byte)0),   //数据包
    PROBEPKG((byte)1),  //探测包
    ACKPKG((byte)2),    //ACK报文
    NAKPKG((byte)3),   //NAK报文
    RETRANS((byte)4);

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
    SplbData(SplbHdr hdr,byte[] data){
        this.hdr = hdr;
        this.data = data;
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
    public boolean endSign = false;
    public int wantedSeq = 1;
    public int arrInOrderCounter = 0;
    public long startTime = 0;
    public long endTime = 0;
    public int lastAckedSeq =0;
    public int recvCounter = 0;
    public int wifiProbeSeq = 1;
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
    long timeStamp;
    LTEProbeTask(LTEControlBlock lteControlBlock,SplbHdr hdr){
        this.lteControlBlock = lteControlBlock;
        this.hdr = hdr;
    }
    @Override
    public void run() {
        hdr.timeStamp = this.timeStamp;
        try {
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
    LTEACKTask(LTEControlBlock lteControlBlock,int ackSeq){
        this.lteControlBlock = lteControlBlock;
        this.ackSeq = ackSeq;
    }

    @Override
    public void run() {
        SplbHdr ackHdr = new SplbHdr(PacketType.ACKPKG, (byte) 0, 0, this.ackSeq,lteControlBlock.wantedSeq);
        byte[] ackMsg = ackHdr.toByteArray();
        DatagramPacket ackPkt = new DatagramPacket(ackMsg,ackMsg.length,lteControlBlock.dstIP,lteControlBlock.dstPort);
        try {
           // System.out.println("发送ACK"+this.ackSeq);
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
    LTEDataTask(LTEControlBlock lteControlBlock,SplbHdr hdr,byte[] msg){
        this.lteControlBlock = lteControlBlock;
        this.hdr = hdr;
        this.msg = msg;
    }
    @Override
    public void run() {
        //无论收到任何数据包，先回复ack；
        lteControlBlock.recvBytes += msg.length;
        LTEACKTask ackTask = new LTEACKTask(lteControlBlock,hdr.pathSeq);
        lteControlBlock.lteAckExecutor.execute(ackTask);

        if(hdr.type == PacketType.DATAPKG){
           // System.out.println("recv data"+hdr.pathSeq + "wanted seq " + wifiControlBlock.wantedSeq);
            if(hdr.pathSeq == lteControlBlock.wantedSeq){   //按序到达的数据包
                lteControlBlock.wantedSeq++;
                lteControlBlock.inorderQueue.put(new SplbData(hdr,msg));
                while(lteControlBlock.outorderQueue.size() > 0){
                    if(lteControlBlock.outorderQueue.peek().hdr.pathSeq < lteControlBlock.wantedSeq){  //检查是否可以从无序队列迁移出来
                        lteControlBlock.outorderQueue.poll();
                    } else if(lteControlBlock.outorderQueue.peek().hdr.pathSeq == lteControlBlock.wantedSeq){
                        SplbData fData = lteControlBlock.outorderQueue.poll();
                        if(fData.hdr.pathSeq == lteControlBlock.wantedSeq){
                            lteControlBlock.wantedSeq++;
                            lteControlBlock.inorderQueue.put(fData);
                            lteControlBlock.lastAckedSeq = fData.hdr.pathSeq;
                            LTEACKTask outOrderAck = new LTEACKTask(lteControlBlock,fData.hdr.pathSeq);
                            lteControlBlock.lteAckExecutor.execute(outOrderAck);
                        }
                    }else{
                        break;
                    }
                }
            }
            else if(hdr.pathSeq > lteControlBlock.wantedSeq){  //乱序到达的数据包
                lteControlBlock.arrInOrderCounter = 0;
                lteControlBlock.recvBytes += msg.length;
                lteControlBlock.outorderQueue.put(new SplbData(hdr,msg));
            }
        }else if(hdr.type == PacketType.RETRANS){
           // System.out.println("收到重传包"+hdr.pathSeq + ",wantedSeq"+lteControlBlock.wantedSeq);
            lteControlBlock.recvBytes += msg.length;
            if(hdr.pathSeq < lteControlBlock.wantedSeq){
                return;
            }else if(hdr.pathSeq == lteControlBlock.wantedSeq){   //按序到达的数据包
                lteControlBlock.wantedSeq++;
                lteControlBlock.inorderQueue.put(new SplbData(hdr,msg));
                while(lteControlBlock.outorderQueue.size() > 0){    //检查是否可以从无序队列迁移出来
                    if(lteControlBlock.outorderQueue.peek().hdr.pathSeq < lteControlBlock.wantedSeq){
                        lteControlBlock.outorderQueue.poll();
                    } else if(lteControlBlock.outorderQueue.peek().hdr.pathSeq == lteControlBlock.wantedSeq){
                        SplbData fData = lteControlBlock.outorderQueue.poll();
                        if(fData.hdr.pathSeq == lteControlBlock.wantedSeq){
                            lteControlBlock.inorderQueue.put(fData);
                            lteControlBlock.wantedSeq++;
                            lteControlBlock.lastAckedSeq = fData.hdr.pathSeq;
                            LTEACKTask outACK = new LTEACKTask(lteControlBlock,fData.hdr.pathSeq);
                            lteControlBlock.lteAckExecutor.execute(outACK);
                        }
                    }else{
                        break;
                    }
                }
            }else{
                lteControlBlock.arrInOrderCounter = 0;
                lteControlBlock.recvBytes += msg.length;
                lteControlBlock.outorderQueue.put(new SplbData(hdr,msg));
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
                lteControlBlock.nowRecv += 1;
                lteControlBlock.recvCounter += 1;
                long timeStamp = System.nanoTime();
                if(lteControlBlock.dstIP == null){
                    InetAddress srcIP = packet.getAddress();
                    int srcPort = packet.getPort();
                    lteControlBlock.dstIP = srcIP;
                    lteControlBlock.dstPort = srcPort;
                }
                byte[] msg = packet.getData();
                SplbHdr hdr = new SplbHdr(msg);
                if(hdr.type == PacketType.PROBEPKG){
                    LTEProbeTask probeTask = new LTEProbeTask(lteControlBlock,hdr);
                    lteControlBlock.lteProbeExecutor.execute(probeTask);
                }else{
                    lteControlBlock.nowRecv += 1;
                    LTEDataTask dataTask = new LTEDataTask(lteControlBlock,hdr,msg);
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
    public boolean endSign = false;
    public int wantedSeq = 1;
    public int arrInOrderCounter = 0;
    public long startTime = 0;
    public long endTime = 0;
    public int lastAckedSeq =0;
    public int recvCounter = 0;
    public int wifiProbeSeq = 1;
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
    long timeStamp;
    WiFiProbeTask(WiFiControlBlock wifiControlBlock,SplbHdr hdr){
        this.wifiControlBlock = wifiControlBlock;
        this.hdr = hdr;
    }
    @Override
    public void run() {
        hdr.timeStamp = this.timeStamp;
        try {
            byte[] probe = hdr.toByteArray();
            DatagramPacket packet = new DatagramPacket(probe,probe.length,wifiControlBlock.dstIP,wifiControlBlock.dstPort);
            wifiControlBlock.socket.send(packet);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
class WifiACKTask implements Runnable{
    WiFiControlBlock wifiControlBlock = null;
    int ackSeq = 0;
    WifiACKTask(WiFiControlBlock wifiControlBlock,int ackSeq){
        this.wifiControlBlock = wifiControlBlock;
        this.ackSeq = ackSeq;
    }

    @Override
    public void run() {
        SplbHdr ackHdr = new SplbHdr(PacketType.ACKPKG, (byte) 1, 0, this.ackSeq,wifiControlBlock.wantedSeq);
        byte[] ackMsg = ackHdr.toByteArray();
        DatagramPacket ackPkt = new DatagramPacket(ackMsg,ackMsg.length,wifiControlBlock.dstIP,wifiControlBlock.dstPort);
        try {
            // System.out.println("发送ACK"+this.ackSeq);
            wifiControlBlock.socket.send(ackPkt);
        } catch (IOException e) {
            // e.printStackTrace();
        }
    }
}
class WifiDataTask implements Runnable{

    WiFiControlBlock wifiControlBlock = null;
    SplbHdr hdr = null;
    byte[] msg;
    WifiDataTask(WiFiControlBlock wifiControlBlock,SplbHdr hdr,byte[] msg){
        this.wifiControlBlock = wifiControlBlock;
        this.hdr = hdr;
        this.msg = msg;
    }
    @Override
    public void run() {
        //无论收到任何数据包，先回复ack；
        wifiControlBlock.recvBytes += msg.length;
        WifiACKTask ackTask = new WifiACKTask(wifiControlBlock,hdr.pathSeq);
        wifiControlBlock.wifiAckExecutor.execute(ackTask);

        if(hdr.type == PacketType.DATAPKG){
            // System.out.println("recv data"+hdr.pathSeq + "wanted seq " + wifiControlBlock.wantedSeq);
            if(hdr.pathSeq == wifiControlBlock.wantedSeq){   //按序到达的数据包
                wifiControlBlock.wantedSeq++;
                wifiControlBlock.inorderQueue.put(new SplbData(hdr,msg));
                while(wifiControlBlock.outorderQueue.size() > 0){
                    if(wifiControlBlock.outorderQueue.peek().hdr.pathSeq < wifiControlBlock.wantedSeq){  //检查是否可以从无序队列迁移出来
                        wifiControlBlock.outorderQueue.poll();
                    } else if(wifiControlBlock.outorderQueue.peek().hdr.pathSeq == wifiControlBlock.wantedSeq){
                        SplbData fData = wifiControlBlock.outorderQueue.poll();
                        if(fData.hdr.pathSeq == wifiControlBlock.wantedSeq){
                            wifiControlBlock.wantedSeq++;
                            wifiControlBlock.inorderQueue.put(fData);
                            wifiControlBlock.lastAckedSeq = fData.hdr.pathSeq;
                            WifiACKTask outOrderAck = new WifiACKTask(wifiControlBlock,fData.hdr.pathSeq);
                            wifiControlBlock.wifiAckExecutor.execute(outOrderAck);
                        }
                    }else{
                        break;
                    }
                }
            }
            else if(hdr.pathSeq > wifiControlBlock.wantedSeq){  //乱序到达的数据包
                wifiControlBlock.arrInOrderCounter = 0;
                wifiControlBlock.recvBytes += msg.length;
                wifiControlBlock.outorderQueue.put(new SplbData(hdr,msg));
            }
        }else if(hdr.type == PacketType.RETRANS){
            // System.out.println("收到重传包"+hdr.pathSeq + ",wantedSeq"+wifiControlBlock.wantedSeq);
            wifiControlBlock.recvBytes += msg.length;
            if(hdr.pathSeq < wifiControlBlock.wantedSeq){
                return;
            }else if(hdr.pathSeq == wifiControlBlock.wantedSeq){   //按序到达的数据包
                wifiControlBlock.wantedSeq++;
                wifiControlBlock.inorderQueue.put(new SplbData(hdr,msg));
                while(wifiControlBlock.outorderQueue.size() > 0){    //检查是否可以从无序队列迁移出来
                    if(wifiControlBlock.outorderQueue.peek().hdr.pathSeq < wifiControlBlock.wantedSeq){
                        wifiControlBlock.outorderQueue.poll();
                    } else if(wifiControlBlock.outorderQueue.peek().hdr.pathSeq == wifiControlBlock.wantedSeq){
                        SplbData fData = wifiControlBlock.outorderQueue.poll();
                        if(fData.hdr.pathSeq == wifiControlBlock.wantedSeq){
                            wifiControlBlock.inorderQueue.put(fData);
                            wifiControlBlock.wantedSeq++;
                            wifiControlBlock.lastAckedSeq = fData.hdr.pathSeq;
                            WifiACKTask outACK = new WifiACKTask(wifiControlBlock,fData.hdr.pathSeq);
                            wifiControlBlock.wifiAckExecutor.execute(outACK);
                        }
                    }else{
                        break;
                    }
                }
            }else{
                wifiControlBlock.arrInOrderCounter = 0;
                wifiControlBlock.recvBytes += msg.length;
                wifiControlBlock.outorderQueue.put(new SplbData(hdr,msg));
            }
        }
        if(wifiControlBlock.startTime ==0 ){
            wifiControlBlock.startTime = System.nanoTime();
        }
    }
}

class WifiTask implements Runnable {
    WiFiControlBlock wifiControlBlock;
    WifiTask(WiFiControlBlock wifiControlBlock){
        this.wifiControlBlock = wifiControlBlock;
    }
    @Override
    public void run() {
        while (!wifiControlBlock.endSign){
            byte[] data = new byte[534];
            DatagramPacket packet = new DatagramPacket(data,data.length);
            try {
                wifiControlBlock.socket.receive(packet);
                wifiControlBlock.nowRecv += 1;
                wifiControlBlock.recvCounter += 1;
                long timeStamp = System.nanoTime();
                if(wifiControlBlock.dstIP == null){
                    InetAddress srcIP = packet.getAddress();
                    int srcPort = packet.getPort();
                    wifiControlBlock.dstIP = srcIP;
                    wifiControlBlock.dstPort = srcPort;
                }
                byte[] msg = packet.getData();
                SplbHdr hdr = new SplbHdr(msg);
                if(hdr.type == PacketType.PROBEPKG){
                    WiFiProbeTask probeTask = new WiFiProbeTask(wifiControlBlock,hdr);
                    wifiControlBlock.wifiProbeExecutor.execute(probeTask);
                }else{
                    wifiControlBlock.nowRecv += 1;
                    WifiDataTask dataTask = new WifiDataTask(wifiControlBlock,hdr,msg);
                    wifiControlBlock.wifiDataExecutor.execute(dataTask);
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
class SpeedTask implements Runnable{
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
                   // System.out.println("--------------------------");
                    int wseq = wifiControlBlock.inorderQueue.size()>0?wifiControlBlock.inorderQueue.peek().hdr.dataSeq:-1;
                    int lseq = lteControlBlock.inorderQueue.size()>0?lteControlBlock.inorderQueue.peek().hdr.dataSeq:-1;
                    System.out.println("lte:"+lteBW+"     :wifi:"+wifiBW+"  |     :lte-seq:"+lseq+"    :wifi-seq:"+wseq);
                    TimeUnit.SECONDS.sleep(1);
                }
            }
        }catch (InterruptedException e) {
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
        while (true){
            if(wq.size()>0){
                if(wq.peek().hdr.dataSeq < wantedSeq){
                    wq.poll();

                }else if(wq.peek().hdr.dataSeq == wantedSeq){
                    SplbData poll = wq.poll();
                   // System.out.println(poll.hdr.dataSeq);
                    wantedSeq++;
                }
            }
            if(lq.size()>0){
                if(lq.peek().hdr.dataSeq < wantedSeq){
                    lq.poll();
                }else if(lq.peek().hdr.dataSeq == wantedSeq){
                    SplbData poll = lq.poll();
                    //System.out.println(poll.hdr.dataSeq);
                    wantedSeq++;
                }
            }
            if(wantedSeq==1000001){
                break;
            }
        }
        long endtime = System.nanoTime();
        long startTime = wifiControlBlock.startTime;
        int useTime = (int)((endtime-startTime)/1000);
        System.out.println("end--------------------------"+useTime);
    }
}
class ServerSock {
    public int port = 18882;
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
        WifiTask wifiTask = new WifiTask(wifiControlBlock);
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