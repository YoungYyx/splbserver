import java.io.DataInputStream; //导入 DataInputStream类
import java.io.DataOutputStream;//导入DataOutputStream类
import java.io.IOException;//导入IOException类
import java.net.ServerSocket;//导入ServerSocket类
import java.net.Socket; //导入Socket 类
import java.util.Scanner; //导入Scanner类
import java.util.concurrent.TimeUnit;

public class TCPServer {//ChatServer类

    private int port = 18888;// 默认服务器端口
    int recvbytes = 0;
    // 提供服务
    public void service() {//创建service方法
        try {// 建立服务器连接
            ServerSocket server = new ServerSocket(port);//创建  ServerSocket类
            Socket socket = server.accept();// 等待客户连接
            try {
                DataInputStream in = new DataInputStream(socket
                        .getInputStream());// 读取客户端传过来信息的DataInputStream
                while (true) {
                    byte[] b = new byte[1024];
                     int len = in.read(b);
                     if(len > 0){
                         this.recvbytes += len;
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

    public static void main(String[] args) {//主程序方法
        TCPServer tpServer = new TCPServer();

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
                            int wifiRecv = tpServer.recvbytes - wifiLastRecv;
                            wifiLastRecv = tpServer.recvbytes;
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
        tpServer.service();
    }
}
