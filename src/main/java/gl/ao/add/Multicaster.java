package gl.ao.add;

import java.net.*;

public class Multicaster {

    public static Object received = null;

    public static void sendAndReceive() {
        receive();
        send();
    }

    public static void receive() {
        class ReceiveMulticaster implements Runnable {

            @Override
            public void run() {
                try {
                    for(;;) {
//                        System.out.println("receiving");
                        // Which port should we listen to
                        int port = 5000;
                        // Which address
                        String group = "225.4.5.6";
                        // Create the socket and bind it to port 'port'.
                        MulticastSocket s = new MulticastSocket(port);
                        // join the multicast group
                        s.joinGroup(InetAddress.getByName(group));
                        // Now the socket is set up and we are ready to receive packets
                        // Create a DatagramPacket and do a receive
                        byte buf[] = new byte[1024];
                        DatagramPacket pack = new DatagramPacket(buf, buf.length);
                        s.receive(pack);
                        // Finally, let us do something useful with the data we just received,
                        // like print it on stdout :-)
                        System.out.println("Received data from: " + pack.getAddress().toString() +
                                ":" + pack.getPort() + " with length: " +
                                pack.getLength());
                        System.out.write(pack.getData(), 0, pack.getLength());
                        System.out.println();
                        // And when we have finished receiving data leave the multicast group and
                        // close the socket
                        s.leaveGroup(InetAddress.getByName(group));
                        s.close();
                        Thread.sleep(2000);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        Thread t = new Thread(new ReceiveMulticaster());
        t.start();

    }

    public static void send() {
        class SendMulticaster implements Runnable {
            @Override
            public void run() {
                try {
                    for(;;) {
//                        System.out.println("sending");
                        // Which port should we send to
                        int port = 5000;
                        // Which address
                        String group = "225.4.5.6";
                        // Which ttl
                        int ttl = 1;
                        // Create the socket but we don't bind it as we are only going to send data
                        MulticastSocket s = new MulticastSocket();
                        // Note that we don't have to join the multicast group if we are only
                        // sending data and not receiving
                        // Fill the buffer with some data
                        byte buf[] = new byte[10];
                        for (int i = 0; i < buf.length; i++) buf[i] = (byte) i;
                        // Create a DatagramPacket
                        DatagramPacket pack = new DatagramPacket(buf, buf.length,
                                InetAddress.getByName(group), port);
                        // Do a send. Note that send takes a byte for the ttl and not an int.
                        s.send(pack, (byte) ttl);
                        // And when we have finished sending data close the socket
                        s.close();
                        Thread.sleep(2000);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        Thread t = new Thread(new SendMulticaster());
        t.start();
    }

}
