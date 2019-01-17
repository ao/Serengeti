package gl.ao.addi.network;

import gl.ao.addi.helpers.Globals;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;

public class Network {

    public Map<String, JSONObject> availableNodes = new HashMap<>();
    public Map<String, JSONObject> clusterNodes = new HashMap<>();
    public String clusterId = "";

    int pingInterval = 10 * 1000;
    int networkTimeout = 5000;
    int successStatus = 200;

    public void initiate() {
        findNodes();
        listenForCommunications();
        coordinateCluster();
    }

    public void findNodes() {
        System.out.println(availableNodes);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (;;) {
                        /**
                         * Ping all availableNodes on the LAN (1-254) for any availableNodes listening on the desired port
                         */
                        getNetworkIPsPorts();
                        Thread.sleep(pingInterval);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void getNetworkIPsPorts() {
        /**
         * Create a list(Map) of these available availableNodes
         */
        final byte[] ip;
        try {
//            InetAddress ina = InetAddress.getLocalHost();
//            ip = ina.getAddress();

//            ip = InetAddress.getByAddress(Globals.getHost4Address().getBytes()).getHostAddress().getBytes(); //.getBytes();

            String __ip = Globals.getHost4Address();
            InetAddress ina = InetAddress.getByName(__ip);
            ip = ina.getAddress();

            System.out.println(Globals.getHost4Address());

        } catch (Exception e) {
            // IP might not have been initialized
            return;
        }

        for(int i=1;i<=254;i++) {
            final int j = i; // i as non-final variable cannot be referenced from inner class
            new Thread(new Runnable() {
                public void run() {
                    ip[3] = (byte) j;

                    String tryingIp = "";

                    try {
                        InetAddress address = InetAddress.getByAddress(ip);
                        String _ip = tryingIp = address.getHostAddress();

                        URL url = new URL("http://" + _ip + ":"+Integer.toString(Globals.port_default));
                        HttpURLConnection con = (HttpURLConnection) url.openConnection();
                        con.setRequestMethod("GET");
                        con.getDoOutput();
                        con.setConnectTimeout(networkTimeout);
                        con.setReadTimeout(networkTimeout);
                        con.setRequestProperty("Content-Type", "application/json");

                        int status = con.getResponseCode();
                        if (status == successStatus) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                            String inputLine;
                            StringBuffer content = new StringBuffer();
                            while ((inputLine = in.readLine()) != null) {
                                content.append(inputLine);
                            }

                            JSONObject jsonObj = new JSONObject(content.toString());
                            JSONObject nodeJSON = (JSONObject) jsonObj.get("this");

                            if (availableNodes.containsKey(_ip)) availableNodes.replace(_ip, nodeJSON);
                            else availableNodes.put(_ip, nodeJSON);

                            in.close();
                        } else {
                            if (availableNodes.containsKey(_ip)) availableNodes.remove(_ip);
                        }

                        con.disconnect();

                    } catch (SocketTimeoutException ste) {
                        // our own little /dev/null
                    } catch (ConnectException ce) {
                        // Connection refused (corporate network blocking?)
                        //System.out.println(tryingIp+" : "+ce.getMessage());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            }).start();
        }
    }

    public void listenForCommunications() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Socket socket = null;
                try {
                    ServerSocket serverSocket = new ServerSocket(Globals.port_communication);

                    while (true) {
                        //Reading the message from the client
                        socket = serverSocket.accept();
                        InputStream is = socket.getInputStream();
                        InputStreamReader isr = new InputStreamReader(is);
                        BufferedReader br = new BufferedReader(isr);
                        String number = br.readLine();
                        System.out.println("Message received from client is " + number);

                        //Multiplying the number by 2 and forming the return message
                        String returnMessage;
                        try {
                            int numberInIntFormat = Integer.parseInt(number);
                            int returnValue = numberInIntFormat * 2;
                            returnMessage = String.valueOf(returnValue) + "\n";
                        } catch (NumberFormatException e) {
                            //Input was not a number. Sending proper message back to client.
                            returnMessage = "Please send a proper number\n";
                        }

                        //Sending the response back to the client.
                        OutputStream os = socket.getOutputStream();
                        OutputStreamWriter osw = new OutputStreamWriter(os);
                        BufferedWriter bw = new BufferedWriter(osw);
                        bw.write(returnMessage);
                        System.out.println("Message sent to the client is " + returnMessage);
                        bw.flush();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                finally {
                    try {
                        if (socket != null && !socket.isClosed()){
                            socket.close();
                        }
                    }
                    catch (Exception e) {}
                }
            }
        });
    }

    public void sendCommunicationsToNode(String nodeIP, String message) {
        Socket socket = null;
        try {
            InetAddress address = InetAddress.getByName(nodeIP);
            socket = new Socket(address, Globals.port_communication);

            //Send the message to the server
            OutputStream os = socket.getOutputStream();
            OutputStreamWriter osw = new OutputStreamWriter(os);
            BufferedWriter bw = new BufferedWriter(osw);

            String sendMessage = message + "\n";
            bw.write(sendMessage);
            bw.flush();
            System.out.println("Message sent to the server : "+sendMessage);

            //Get the return message from the server
            InputStream is = socket.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String responseMessage = br.readLine();
            System.out.println("Message received from the server : " +responseMessage);
        }
        catch (Exception exception) {
            exception.printStackTrace();
        }
        finally {
            //Closing the socket
            try {
                socket.close();
            }
            catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void coordinateCluster() {
        /**
         * 1. Send out a request to 2 other random availableNodes to see if they are part of a cluster yet
         *     1. If they are not then they are auto assigned
         *     2. Otherwise they return who they are assigned to, then repeat the process
         */
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {

                    // check if clusterNodes has 3 in total and if all are connected/healthy
                    if (clusterNodes.size()>0) {
                        // see if we can connect to existing clusterNodes
                        // try connect to clusterNodes and unset them if there's an issue

                        // if problem with node then `clusterNodes.remove(index)`
                    }
                    if (clusterNodes.size()==2) {
                        // this means all nodes are healthy, no need to connect again
                        return;
                    }

                    if (availableNodes.size()>0) {
                        // There are at least 3 nodes available to form a cluster
                        List<String> list = new ArrayList<>();
                        for (String key: availableNodes.keySet()) {
                            JSONObject json = availableNodes.get(key);
                            String a = "";
//                            if (clusterId.equals("")) {
//                                //this node is not part of a cluster
//                            }
//                        if (json.get("ip"))
                        }
                    }

                    Thread.sleep(pingInterval);


//                    availableNodes.get()
//                    for (;;) {
//
//
//                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void getNetworkIPsOnly() {
        final byte[] ip;
        try {
            ip = InetAddress.getLocalHost().getAddress();
        } catch (Exception e) {
            // exit method, otherwise "ip might not have been initialized"
            return;
        }

        for(int i=1;i<=254;i++) {
            // i as non-final variable cannot be referenced from inner class
            final int j = i;
            // new thread for parallel execution
            new Thread(new Runnable() {
                public void run() {
                    try {
                        ip[3] = (byte) j;
                        InetAddress address = InetAddress.getByAddress(ip);

                        String output = address.toString().substring(1);
                        if (address.isReachable(5000)) {
                            System.out.println(output + " is on the network");
                        } else {
                            System.out.println("Not Reachable: "+output);
                        }



                    } catch (Exception e) {
                        System.out.println(ip);
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }

}
