import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
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
                    while(true){
                        byte[] data = new byte[534];
                        DatagramPacket packet = new DatagramPacket(data,data.length);
                        try {
                            wifiSocket.receive(packet);
                            byte[] msg = packet.getData();
                            int len = packet.getLength();
                            if(len > 0){
                                recvbytes += len;
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
                                // System.out.println("--------------------------");
                                System.out.println(wifiBW);
                                TimeUnit.SECONDS.sleep(1);
                            }
                        }
                    }catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }
}
