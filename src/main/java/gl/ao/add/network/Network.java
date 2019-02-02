package gl.ao.add.network;

import gl.ao.add.helpers.Globals;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;

public class Network {

    public Sender sender;
    public Receiver receiver;

    public Map<String, JSONObject> availableNodes = new HashMap<>();
    public Map<String, JSONObject> clusterNodes = new HashMap<>();
    public String clusterId = "";

    public String myIP = null;
    public InetAddress myINA = null;

    public String coordinator = "";

    int pingInterval = 5 * 1000;
    int networkTimeout = 2500;
    int successStatus = 200;


    public void initiate() {
        try {
            myIP = Globals.getHost4Address();
            myINA = InetAddress.getByName(myIP);
        } catch (Exception e) {}

        if (myIP == null || myIP.equals("127.0.0.1")) {
            System.out.println("This node is not connected to a network. It will therefore only function locally.");
        }

        try {
            receiver = new Receiver(myIP, myINA);
            sender = new Sender();
        } catch (Exception e) {
            e.printStackTrace();
        }

        findNodes();
    }

    public void findNodes() {
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
                        System.out.println(availableNodes);
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

        final long currentTime = System.currentTimeMillis();

        for(int i=1;i<=254;i++) {
            final int j = i; // i as non-final variable cannot be referenced from inner class
            Thread t = new Thread(new Runnable() {
                public void run() {
                    ip[3] = (byte) j;

                    try {
                        InetAddress address = InetAddress.getByAddress(ip);
                        String _ip = address.getHostAddress();

                        URL url = new URL("http://" + _ip + ":"+Globals.port_default);
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
                            nodeJSON.put("last_checked", currentTime);

                            String nodeId = nodeJSON.get("id").toString();

//                            System.out.println(nodeId);
//                            System.out.println(currentTime);
                            System.out.println("Added: "+nodeId+" "+_ip);


                            if (availableNodes.containsKey(nodeId)) availableNodes.replace(nodeId, nodeJSON);
                            else availableNodes.put(nodeId, nodeJSON);

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
        int threadcompletecount = 0;
        for (Thread t: threads) {
            try {
                t.join();
                threadcompletecount++;
                if (threadcompletecount==254) {
                    System.out.println("Completed");

                    if (availableNodes.size()>0) {
                        for (String key : availableNodes.keySet()) {
                            JSONObject json = availableNodes.get(key);
                            long _last_checked = Long.parseLong(json.get("last_checked").toString());





                            try {
                                String data = "data=Hello+World!";
                                URL url2 = new URL("http://" + json.get("ip").toString() + ":" + Globals.port_default + "/post");
                                HttpURLConnection con2 = (HttpURLConnection) url2.openConnection();
                                con2.setRequestMethod("POST");
                                con2.setDoOutput(true);
                                con2.setConnectTimeout(networkTimeout);
                                con2.getOutputStream().write(data.getBytes("UTF-8"));
                                con2.getInputStream();
//                                con2.setRequestProperty("Content-Type", "application/json");
                                System.out.println("Test");


                            } catch (ConnectException ce) {
                                // do nothing for now
                                // the node we are trying to send messages to appears to be down?
                            } catch (Exception e) {
                                e.printStackTrace();
                            }






                            if (_last_checked<currentTime) {
                                System.out.println("delete: "+json.get("id")+" "+json.get("ip"));
                                availableNodes.remove(json.get("id"));
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {}
        }

//        coordinateCluster();
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

                        if (coordinator.equals(myIP)) {
                            System.out.println("I AM THE COORDINATOR!");
                        }

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
