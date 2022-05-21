import java.io.*;
import java.net.*;
import java.util.concurrent.TimeUnit;
class LTETCPServer{
    public int port = 16000;// 默认服务器端口
    int recvbytes = 0;
    // 提供服务
    public void service() {//创建service方法
        try {// 建立服务器连接
            ServerSocket server = new ServerSocket(port);//创建  ServerSocket类
            Socket socket = server.accept();// 等待客户连接
            try {
                DataInputStream in = new DataInputStream(socket
                        .getInputStream());// 读取客户端传过来信息的DataInputStream
                File file = new File("/home/test.mkv");
                FileOutputStream os = null;
                try {
                    os = new FileOutputStream(file);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
                while (true) {
                    byte[] b = new byte[1024];
                    int len = in.read(b);
                    if(len > 0){
                        this.recvbytes += len;
                        os.write(b,0,len);
                    }
                }
            } finally {// 建立连接失败的话不会执行socket.close();
                socket.close();//关闭连接
                server.close();//关闭
            }
        } catch (IOException e) {//捕获异常
            e.printStackTrace();
        }
    }
}
class WIFITCPServer{
    public int port = 17000;// 默认服务器端口
    int recvbytes = 0;
    // 提供服务
    public void service() {//创建service方法
        try {// 建立服务器连接
            ServerSocket server = new ServerSocket(port);//创建  ServerSocket类
            Socket socket = server.accept();// 等待客户连接
            try {
                DataInputStream in = new DataInputStream(socket
                        .getInputStream());// 读取客户端传过来信息的DataInputStream
                File file = new File("/home/test.mkv");
                FileOutputStream os = null;
                try {
                    os = new FileOutputStream(file);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
                while (true) {
                    byte[] b = new byte[1024];
                    int len = in.read(b);
                    if(len > 0){
                        this.recvbytes += len;
                        os.write(b,0,len);
                    }
                }
            } finally {// 建立连接失败的话不会执行socket.close();
                socket.close();//关闭连接
                server.close();//关闭
            }
        } catch (IOException e) {//捕获异常
            e.printStackTrace();
        }
    }
}
public class TCPServer {//ChatServer类

    public static void main(String[] args) throws UnknownHostException, SocketException {//主程序方法
        LTETCPServer ltetcpServer = new LTETCPServer();
        WIFITCPServer wifitcpServer = new WIFITCPServer();

        new Thread(()->{
            ltetcpServer.service();
        }).start();
        new Thread(()->{
            wifitcpServer.service();
        }).start();

        InetAddress address = InetAddress.getByName("127.0.0.1");
        int dstport = 15000;
        DatagramSocket speedSocket = new DatagramSocket(12345);
        new Thread(() -> {
            int lteLastRecv = 0;
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
                        int lteRecv = ltetcpServer.recvbytes - lteLastRecv;
                        lteLastRecv = ltetcpServer.recvbytes;
                        int wifiRecv = wifitcpServer.recvbytes - wifiLastRecv;
                        wifiLastRecv = wifitcpServer.recvbytes;
                        double lteBW =(lteRecv * 8.0) / interval;
                        double wifiBW =(wifiRecv * 8.0) / interval;
                        String speed = lteBW+":"+wifiBW;
                        byte[] data = speed.getBytes();
                        int len = data.length;
                        DatagramPacket packet = new DatagramPacket(data,len,address,dstport);
                        speedSocket.send(packet);
                        System.out.println(lteBW);
                        TimeUnit.SECONDS.sleep(1);
                    }
                }
            }catch (InterruptedException | IOException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
