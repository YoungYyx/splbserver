import java.io.*;
import java.net.*;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class UDPServer {
    private static int recvbytes = 0;
    private static DatagramSocket wifiSocket;
    public static void main(String[] args) {
        try {
            wifiSocket = new DatagramSocket(19000);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    File file = new File("/home/test.mp4");
                    FileOutputStream os = null;
                    try {
                        os = new FileOutputStream(file);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                    while(true){
                        byte[] data = new byte[512];
                        DatagramPacket packet = new DatagramPacket(data,data.length);
                        try {
                            wifiSocket.receive(packet);
                            byte[] msg = packet.getData();
                            int len = packet.getLength();
                            if(len > 0){
                                recvbytes += len;
                                os.write(msg,0,len);
                            }

                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            }).start();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    InetAddress address = null;
                    int dstport = 15000;
                    DatagramSocket speedSocket = null;
                    try {
                        address = InetAddress.getByName("127.0.0.1");
                        speedSocket = new DatagramSocket(12345);
                    } catch (UnknownHostException|SocketException e) {
                        e.printStackTrace();
                    }
                    int wifiLastRecv = 0;
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
                                int wifiRecv = UDPServer.recvbytes - wifiLastRecv;
                                wifiLastRecv = UDPServer.recvbytes;
                                double wifiBW =(wifiRecv * 8.0) / interval;
                                String speed = "0:"+wifiBW;
                                byte[] data = speed.getBytes();
                                int len = data.length;
                                DatagramPacket packet = new DatagramPacket(data,len,address,dstport);
                                speedSocket.send(packet);
                                TimeUnit.SECONDS.sleep(1);
                            }
                        }
                    }catch (InterruptedException | IOException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }
}
