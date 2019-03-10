package gl.ao.add.network;

import gl.ao.add.helpers.Globals;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class Receiver {

    public Receiver(String myIP, InetAddress myINA) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket socket = null;
                try {

                    ServerSocket serverSocket = new ServerSocket(Globals.port_communication, 50, myINA);
                    System.out.println("Listening on "+myIP+":"+Integer.toString(Globals.port_communication));

                    for(;;) {
                        //Reading the message
                        socket = serverSocket.accept();
                        InputStream is = socket.getInputStream();
                        InputStreamReader isr = new InputStreamReader(is);
                        BufferedReader br = new BufferedReader(isr);
                        String message = br.readLine();
                        System.out.println("Message received: " + message);


//                        if (!message.equals("")) {
//                            if (message.startsWith("JOIN_CLUSTER")) {
//                                if (clusterId.equals("")) {
//                                    String _joinClusterId = message.replace("JOIN_CLUSTER=", "");
//                                    clusterId = _joinClusterId;
//
//                                }
//                            }
//                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                finally {
                    try {
                        if (socket != null && !socket.isClosed()) socket.close();
                    } catch (Exception e) {}
                }
            }
        }).start();
    }

}
