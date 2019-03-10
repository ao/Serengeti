package gl.ao.add.network;

import gl.ao.add.helpers.Globals;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class Sender {

    private ServerSocket server;

    public Sender() {

    }

    public void sendToNode(String nodeIP, String message) {
        Socket socket = null;
        try {
            InetAddress address = InetAddress.getByName(nodeIP);
            System.out.println(address);
            socket = new Socket(address, Globals.port_communication);

            OutputStream os = socket.getOutputStream();
            OutputStreamWriter osw = new OutputStreamWriter(os);
            BufferedWriter bw = new BufferedWriter(osw);

            String sendMessage = "";

            boolean messageSent = false;

            if (!message.equals("")) {
                if (message.startsWith("JOIN_CLUSTER")) {

                    sendMessage = message + "\n";

                    String _clusterId = message.replace("JOIN_CLUSTER=", "");

                    bw.write(sendMessage);
                    bw.flush();
                    System.out.println("Message sent to the server : "+sendMessage);

                    messageSent = true;
                }
            }
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
        finally {
            //Closing the socket
            try {
                if (socket!=null) socket.close();
            }
            catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

}
