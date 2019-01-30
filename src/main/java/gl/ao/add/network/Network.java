package gl.ao.add.network;

import gl.ao.add.helpers.Globals;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;

public class Network {

    public Map<String, JSONObject> availableNodes = new HashMap<>();
    public Map<String, JSONObject> clusterNodes = new HashMap<>();
    public String clusterId = "";
    public static String coordinator = "";

    int pingInterval = 10 * 1000;
    int networkTimeout = 5000;
    int successStatus = 200;

    public String myIP = null;
    public InetAddress myINA = null;

    public void initiate() {
        try {
            myIP = Globals.getHost4Address();
            myINA = InetAddress.getByName(myIP);
        } catch (Exception e) {}

        if (myIP == null || myIP.equals("127.0.0.1")) {
            System.out.println("This node is not connected to a network.");
            System.exit(1);
        }

        listenForCommunications();
        findNodes();
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
            ip = myINA.getAddress();
        } catch (Exception e) {
            // IP might not have been initialized
            return;
        }

        List<Thread> threads = new ArrayList<>();

        for(int i=1;i<=254;i++) {
            final int j = i; // i as non-final variable cannot be referenced from inner class
            Thread t = new Thread(new Runnable() {
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

//                            String nodeId = nodeJSON.get("id").toString();

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
                    } catch (NoRouteToHostException e) {
                        //java.net.NoRouteToHostException: No route to host (Host unreachable)
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            });

            t.start();
            threads.add(t);
        }
        for (Thread t: threads) {
            try {
                t.join();
            } catch (InterruptedException e) {}
        }

        coordinateCluster();
    }

    public void listenForCommunications() {
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

                        if (!message.equals("")) {
                            if (message.startsWith("JOIN_CLUSTER")) {
                                if (clusterId.equals("")) {
                                    String _joinClusterId = message.replace("JOIN_CLUSTER=", "");
                                    clusterId = _joinClusterId;

                                }
                            }
                        }
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

    public void sendCommunicationsToNode(String nodeIP, String message) {
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
                    //String _clusterId = message.replace("JOIN_CLUSTER=", "");

                    sendMessage = message + "\n";
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

    public String generateClusterID() {
        return UUID.randomUUID().toString();
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
                    for (;;) {


                        if (availableNodes.size()>0) {
                            //make the lowest IP in the availableNodes the coordinator
                            Integer coordinator_Int = 0;
                            for (String key: availableNodes.keySet()) {
                                JSONObject json = availableNodes.get(key);
                                String _ip = json.get("ip").toString();
                                if (coordinator_Int==0) {
                                    Integer new_coordinator = new Integer(_ip.replace(".",""));
                                    if (new_coordinator < coordinator_Int || coordinator_Int==0) {
                                        coordinator_Int = new_coordinator;
                                        coordinator = _ip;
                                    }
                                }
                            }
                        }




//                        if (availableNodes.size()>0) {
//                            // There are at least 3 nodes available to form a cluster
//                            List<String> list = new ArrayList<>();
//                            for (String key: availableNodes.keySet()) {
//                                JSONObject json = availableNodes.get(key);
//                                String a = "";
//                                if (!key.equals(myIP)) {
//                                    if (json.get("cluster").equals("")) {
//                                        clusterId = generateClusterID();
//                                        sendCommunicationsToNode(key, "JOIN_CLUSTER=" + clusterId);
//                                    }
//                                }
//                            }
//                        }

                        Thread.sleep(pingInterval);
                    }
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
