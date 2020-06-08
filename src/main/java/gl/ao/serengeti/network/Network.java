package gl.ao.serengeti.network;

import gl.ao.serengeti.helpers.Globals;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;

public class Network {

    public Map<String, JSONObject> availableNodes = new HashMap<>();

    public String myIP = null;
    public InetAddress myINA = null;

    public static long latency = 0;
    public static boolean latencyRun = false;

    int pingInterval = 5 * 1000;
    int networkTimeout = 2500;
    int successStatus = 200;

    private boolean hasPerformedNetworkSync = false;
    public static boolean online = false;

    /***
     * Initialise local IP address and begin finding available nodes on the network
     */
    public void init() {
        try {
            myIP = Globals.getHost4Address();
            myINA = InetAddress.getByName(myIP);
        } catch (Exception e) {}

        if (myIP == null || myIP.equals("127.0.0.1")) {
            System.out.println("This node is not connected to a network. It will therefore only function locally.");
        }

        findNodes();
    }

    /***
     * Request all Database and Table meta-data from the network;
     * in order to make sure the correct data structure is present before allowing node to contribute to network
     */
    public void requestNetworkMetas() {
        if (hasPerformedNetworkSync==false) {
            hasPerformedNetworkSync = true;

            final Map<String, JSONObject> _availableNodes = this.availableNodes;

            System.out.println("\nStartup: Checking to see if local data is stale..");

            new Thread(new Runnable() {
                @Override
                public void run() {
                    int changesFound = 0;
                    for (Map.Entry<String, JSONObject> node: _availableNodes.entrySet()) {
                        if (!gl.ao.serengeti.Serengeti.server.server_constants.id.equals(node.getKey())) {
                            JSONObject jsonObject = node.getValue();

                            try {
                                HttpURLConnection con = (HttpURLConnection) new URL("http://" + jsonObject.getString("ip") + ":" + Globals.port_default + "/meta").openConnection();
                                con.setRequestMethod("GET");
                                con.getDoOutput();
                                con.setConnectTimeout(networkTimeout);
                                con.setReadTimeout(networkTimeout);
                                con.setRequestProperty("Content-Type", "application/json");

                                if (con.getResponseCode() == successStatus) {
                                    BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                                    String inputLine;
                                    StringBuffer content = new StringBuffer();
                                    while ((inputLine = in.readLine()) != null) {
                                        content.append(inputLine);
                                    }

                                    JSONObject jsonMeta = new JSONObject(content.toString()).getJSONObject("meta");

                                    Iterator<String> keys = jsonMeta.keys();
                                    while(keys.hasNext()) {
                                        String db = keys.next();
                                        if (jsonMeta.get(db) instanceof JSONArray) {
                                            JSONArray jsonArr = (JSONArray) jsonMeta.get(db);

                                            if (!gl.ao.serengeti.Serengeti.storage.databaseExists(db)) {
                                                System.out.println("Startup: Creating missing database '"+db+"'");
                                                gl.ao.serengeti.Serengeti.storage.createDatabase(db, true);
                                                changesFound++;
                                            }

                                            for (int i = 0; i < jsonArr.length(); i++) {
                                                String table = jsonArr.getString(i);
                                                if (!gl.ao.serengeti.Serengeti.storage.tableExists(db, table)) {
                                                    System.out.println("Startup: Creating missing table '"+table+"' for database '"+db+"'");
                                                    gl.ao.serengeti.Serengeti.storage.createTable(db, table, true);
                                                    changesFound++;

                                                    String row_replicas = gl.ao.serengeti.Serengeti.network.communicateQueryLogSingleNode( jsonObject.getString("id"), jsonObject.getString("ip"), new JSONObject(){{
                                                        put("type", "SendTableReplicaToNode");
                                                        put("db", db);
                                                        put("table", table);
                                                        put("node_id", gl.ao.serengeti.Serengeti.server.server_constants.id);
                                                        put("node_ip", gl.ao.serengeti.Serengeti.network.myIP);
                                                    }}.toString() );

                                                    try {
                                                        JSONObject jsonRowsReplica = new JSONObject(row_replicas);
                                                        Iterator<String> jkeys = jsonRowsReplica.keys();
                                                        while (jkeys.hasNext()) {
                                                            String jrow_id = jkeys.next();
                                                            JSONObject _json = new JSONObject(jsonRowsReplica.getString(jrow_id));
                                                            gl.ao.serengeti.Serengeti.storage.tableReplicaObjects.get(db+"#"+table).insertOrReplace(jrow_id, _json);
                                                        }
                                                    } catch (Exception e) {}
                                                }
                                            }
                                        }
                                    }

                                    in.close();
                                }



                            } catch (Exception e) {
                                e.printStackTrace();
                            }

                        }
                    }
                    gl.ao.serengeti.Serengeti.network.online = true;
                    System.out.println("Startup: Completed with "+changesFound+" changes found");
                    gl.ao.serengeti.Serengeti.server.serve();
                }
            }).start();
        }
    }

    /***
     * Find all nodes on the network
     * Use a separate thread
     */
    public void findNodes() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (;;) {
                        // Ping all nodes on the LAN (1-254) for any availableNodes listening on the desired port
                        getNetworkIPsPorts();
                        Thread.sleep(pingInterval);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public HttpURLConnection getURLConnection(String ip) throws Exception {
        URL url = new URL("http://" + ip + ":"+Globals.port_default);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");
        con.getDoOutput();
        con.setConnectTimeout(networkTimeout);
        con.setReadTimeout(networkTimeout);
        con.setRequestProperty("Content-Type", "application/json");
        return con;
    }

    /***
     * Scan the whole network using the current master NIC
     * Search on port 1985
     */
    public void getNetworkIPsPorts() {
        // Create a list(Map) of these available availableNodes

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

            String[] ipParts = myIP.split("\\.");
            final String baseIP = ipParts[0]+"."+ipParts[1]+"."+ipParts[2]+".";

            gl.ao.serengeti.Serengeti.network.latencyRun = true;
            gl.ao.serengeti.Serengeti.network.latency = 0;
            Thread t = new Thread(new Runnable() {
                public void run() {

                    try {
                        String _ip = baseIP+j;

                        long startTime = System.currentTimeMillis();

                        HttpURLConnection con = getURLConnection(_ip);
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

                            if (availableNodes.containsKey(nodeId)) availableNodes.replace(nodeId, nodeJSON);
                            else availableNodes.put(nodeId, nodeJSON);

                            in.close();
                        }

                        if (gl.ao.serengeti.Serengeti.network.latencyRun) {
                            long elapsedTime = System.currentTimeMillis() - startTime;
                            if (elapsedTime> gl.ao.serengeti.Serengeti.network.latency) {
                                gl.ao.serengeti.Serengeti.network.latency = elapsedTime;
                            }
                        }


                        con.disconnect();

                    } catch (SocketTimeoutException ste) {
                        // our own little /dev/null
                    } catch (ConnectException ce) {
                        // Connection refused (corporate network blocking?)
                        //System.out.println(tryingIp+" : "+ce.getMessage());
                    } catch (NoRouteToHostException e) {
                        //java.net.NoRouteToHostException: No route to host (Host unreachable)
                    } catch (IOException ioe) {
                        //java.net.SocketException: Connection reset by peer (connect failed)
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
                    gl.ao.serengeti.Serengeti.network.latencyRun = false;
                    if (availableNodes.size() > 0) {
                        for (String key : availableNodes.keySet()) {
                            JSONObject json = availableNodes.get(key);
                            long _last_checked = Long.parseLong(json.get("last_checked").toString());

                            if (_last_checked < currentTime) {
                                availableNodes.remove(json.get("id"));
                                gl.ao.serengeti.Serengeti.storageReshuffle.queueLostNode(json);
                            }
                        }

                        requestNetworkMetas();
                    } else if (gl.ao.serengeti.Serengeti.network.online==false) {
                        gl.ao.serengeti.Serengeti.network.online = true;
                        gl.ao.serengeti.Serengeti.network.hasPerformedNetworkSync = true;
                        System.out.println("\nStartup: Completed");
                        System.out.println(" - No other nodes found on the network, waiting..");
                        gl.ao.serengeti.Serengeti.server.serve();
                    }
                }
            }
            catch (InterruptedException e) {}
            catch (ConcurrentModificationException cme) {}
        }
    }

    public boolean nodeIsOnline(String ip) {
        try {
            HttpURLConnection con = getURLConnection(ip);
            int status = con.getResponseCode();

            if (status == successStatus) return true;

        } catch (Exception e) {}
        return false;
    }

    /**
     * Communicate Query Log to All Nodes
     * @param jsonString
     * @return JSONArray
     */
    public JSONArray communicateQueryLogAllNodes(String jsonString) {
        JSONArray response = new JSONArray();
        try {
            final Map<String, JSONObject> _availableNodes = this.availableNodes;
            if (_availableNodes.size() > 0) {
                for (String key : _availableNodes.keySet()) {
                    JSONObject json = _availableNodes.get(key);
                    response.put(communicateQueryLogSingleNode(json.getString("id"), json.getString("ip"), jsonString));
                }
            }
        } catch (ConcurrentModificationException cme) {
            //cme.printStackTrace();
        }
        return response;
    }

    /**
     * Communicate Query Log to a Single Node
     * @param id
     * @param ip
     * @param jsonString
     * @return String
     */
    public String communicateQueryLogSingleNode(String id, String ip, String jsonString) {
        // sometimes we don't have a secondary node to send data to..
        if (id.equals("") || ip.equals("")) return "";

        String response = "";
        try {
            URL url2 = new URL("http://" + ip + ":" + Globals.port_default + "/post");
            HttpURLConnection con2 = (HttpURLConnection) url2.openConnection();
            con2.setRequestMethod("POST");
            con2.setDoOutput(true);
            con2.setConnectTimeout(networkTimeout);
            con2.getOutputStream().write(jsonString.getBytes("UTF-8"));

            BufferedReader br = new BufferedReader(new InputStreamReader(con2.getInputStream()));
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                response += line;
            }
            return response;
        } catch (SocketException se) {
            //System.out.println("Socket Exception (communicateQueryLogSingleNode): " + se.getMessage());
            return "";
        } catch (IOException ioe) {
            System.out.println("IOException: " + ioe.getMessage()+ " > Tried: Communicating to " + ip + ": " + jsonString );
            return "";
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    /**
     * Get Myself as a Node
     * @return JSONObject
     */
    public JSONObject getSelfNode() {
        JSONObject json = new JSONObject();
        json.put("id", gl.ao.serengeti.Serengeti.server.server_constants.id);
        json.put("ip", myIP);

        return json;
    }

    /**
     * Get Primary and Secondary Nodes
     * @return JSONObject
     */
    public JSONObject getPrimarySecondary() {
        JSONObject json = new JSONObject();

        JSONArray nodes = getRandomAvailableNodes(2);
        if (nodes == null || nodes.length()<2) {
            JSONObject myself = getSelfNode();
            json.put("primary", myself);
            json.put("secondary", new JSONObject() {{
                put("id", "");
                put("ip", "");
            }});
        } else {
            json.put("primary", nodes.get(0));
            json.put("secondary", nodes.get(1));
        }

        return json;
    }

    /**
     * Get Random Available Nodes
     * @param amount
     * @return JSONArray
     */
    public JSONArray getRandomAvailableNodes(int amount) {
        Map<String, JSONObject> an = new HashMap<String, JSONObject>();
        an.putAll(this.availableNodes);

        if (an.size()>=2) {
            JSONArray nodes = new JSONArray();
            List<JSONObject> list = new ArrayList<JSONObject>();
            for (String key: an.keySet()) {
                list.add(an.get(key));
            }
            Collections.shuffle(list);

            nodes.put(list.get(0));
            nodes.put(list.get(1));

            return nodes;
        }

        return null;
    }
    public JSONObject getRandomAvailableNode() {
        Map<String, JSONObject> an = new HashMap<String, JSONObject>();
        an.putAll(this.availableNodes);

        if (an.size() > 0) {
            // Should first remove `self` from the list
            Iterator it=an.entrySet().iterator();
            while(it.hasNext()) {
                Map.Entry<String, JSONObject> item = (Map.Entry<String, JSONObject>) it.next();
                if (item.getValue().get("ip").toString().equals(myIP)) {
                    it.remove();
                }
            }

            if (an.size()==0) return null; // this can happen when there was only 1 node in the list and it was ourselves!

            List<JSONObject> list = new ArrayList<JSONObject>();
            for (String key: an.keySet()) {
                list.add(an.get(key));
            }

            // Shuffle and send the first one back
            Collections.shuffle(list);
            return (JSONObject) list.get(0);
        }

        return null;
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


    public String getIPFromUUID(String uuid) {
        try {
            return availableNodes.get(uuid).getString("ip");
        } catch (NullPointerException npe) {
            System.out.println("Network is not ready to handle this request..");
        }
        return "";
    }

}
