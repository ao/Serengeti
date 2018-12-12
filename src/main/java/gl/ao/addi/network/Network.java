package gl.ao.addi.network;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

public class Network {

    public Map<String, JSONObject> nodes = new HashMap<>();

    public void analyse() {
        System.out.println(nodes);
        new Thread(new Runnable() {
            @Override
            public void run() {
                getNetworkIPsPorts();
                try {
                    Thread.sleep(10 * 1000);
                    analyse();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

    }

    public void getNetworkIPsPorts() {
        final byte[] ip;
        try {
            InetAddress ina = InetAddress.getLocalHost();
            ip = ina.getAddress();
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
                    ip[3] = (byte) j;

                    String tryingIp = "";

                    try {
                        InetAddress address = InetAddress.getByAddress(ip);
                        String _ip = tryingIp = address.getHostAddress();

                        URL url = new URL("http://" + _ip + ":1985");
                        HttpURLConnection con = (HttpURLConnection) url.openConnection();
                        con.setRequestMethod("GET");
                        con.getDoOutput();
                        con.setConnectTimeout(5000);
                        con.setReadTimeout(5000);
                        con.setRequestProperty("Content-Type", "application/json");

                        int status = con.getResponseCode();
                        if (status == 200) {
                            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
                            String inputLine;
                            StringBuffer content = new StringBuffer();
                            while ((inputLine = in.readLine()) != null) {
                                content.append(inputLine);
                            }

                            JSONObject jsonObj = new JSONObject(content.toString());
                            JSONObject nodeJSON = (JSONObject) jsonObj.get("this");


                            if (nodes.containsKey(_ip)) nodes.replace(_ip, nodeJSON);
                            else nodes.put(_ip, nodeJSON);

                            in.close();
                        } else {
                            if (nodes.containsKey(_ip)) nodes.remove(_ip);
                        }

                        con.disconnect();


                    } catch (SocketTimeoutException ste) {
                        // our own little /dev/null
                    } catch (ConnectException ce) {
                        // Connection refused (corporate network blocking?)
                        System.out.println(tryingIp+" : "+ce.getMessage());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
            }).start();
        }
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
