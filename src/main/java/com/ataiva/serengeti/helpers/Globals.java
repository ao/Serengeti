package com.ataiva.serengeti.helpers;

import com.ataiva.serengeti.Serengeti;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.*;

/***
 * Static Globals
 * Used for global reuse and configuration
 */
public class Globals {

    public static String name = "Serengeti";
    public static String meta_extention = ".ddbm";

    public static String data_path = System.getProperty("user.dir") != null ? System.getProperty("user.dir")+"/data/" : "./data/";

    public static int piece_size = 1024 * 1024;

    public static String replica_filename = "replica.file";
    public static String storage_filename = "storage.file";
    public static String index_filename = "index.file";

    public static int port_default = 1985;

    /***
     * Convert to Bytes
     * @param object
     * @return byte[]
     */
    public static byte[] convertToBytes(Object object) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutput out = new ObjectOutputStream(bos);
            out.writeObject(object);
            return bos.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new byte[1];
    }

    /***
     * Convert From Bytes
     * @param bytes
     * @return Object
     */
    public static Object convertFromBytes(byte[] bytes) {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            ObjectInput in = new ObjectInputStream(bis);
            Object obj = in.readObject();
            System.out.print("Loading persisted object \t: ");
            System.out.print(obj);
            System.out.println();
            return obj;
        } catch (EOFException eof) {
            eof.printStackTrace();
//            System.out.println( new String(bytes) );
            // Don't exit the JVM, just return null to indicate an error
            return null;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new Object();
    }

    /***
     * JSONObject to Map
     * @param json
     * @return
     * @throws JSONException
     */
    public static Map<String, Object> jsonToMap(JSONObject json) throws JSONException {
        Map<String, Object> retMap = new HashMap<String, Object>();

        if(json != JSONObject.NULL) {
            retMap = toMap(json);
        }
        return retMap;
    }

    /***
     * JSONObject to Map
     * @param object
     * @return
     * @throws JSONException
     */
    public static Map<String, Object> toMap(JSONObject object) throws JSONException {
        Map<String, Object> map = new HashMap<String, Object>();

        Iterator<String> keysItr = object.keys();
        while(keysItr.hasNext()) {
            String key = keysItr.next();
            Object value = object.get(key);

            if(value instanceof JSONArray) {
                value = toList((JSONArray) value);
            }

            else if(value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            map.put(key, value);
        }
        return map;
    }

    /***
     * JSONArray to List
     * @param array
     * @return
     * @throws JSONException
     */
    public static List<Object> toList(JSONArray array) throws JSONException {
        List<Object> list = new ArrayList<Object>();
        for(int i = 0; i < array.length(); i++) {
            Object value = array.get(i);
            if(value instanceof JSONArray) {
                value = toList((JSONArray) value);
            }

            else if(value instanceof JSONObject) {
                value = toMap((JSONObject) value);
            }
            list.add(value);
        }
        return list;
    }



    /**
     * Returns this host's non-loopback IPv4 addresses.
     * @return List<Inet4Address>
     * @throws SocketException
     */
    private static List<Inet4Address> getInet4Addresses() throws SocketException {
        List<Inet4Address> ret = new ArrayList<Inet4Address>();

        Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface netint : Collections.list(nets)) {
            Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
            for (InetAddress inetAddress : Collections.list(inetAddresses)) {
                if (inetAddress instanceof Inet4Address && !inetAddress.isLoopbackAddress()) {
                    ret.add((Inet4Address)inetAddress);
                }
            }
        }

        return ret;
    }

    /**
     * Returns this host's first non-loopback IPv4 address string in textual
     * representation, preferring 192.168.x.x addresses.
     * @return String
     * @throws SocketException
     */
    public static String getHost4Address() throws SocketException {
        List<Inet4Address> inet4 = getInet4Addresses();
        
        // First, try to find a 192.168.x.x address
        for (Inet4Address addr : inet4) {
            String ip = addr.getHostAddress();
            if (ip.startsWith("192.168.")) {
                return ip;
            }
        }
        
        // If no 192.168.x.x address is found, return the first available address
        return !inet4.isEmpty()
                ? inet4.get(0).getHostAddress()
                : null;
    }

    /***
     * Helper to recursively delete a directory and all files within
     * @param path
     */
    static public void deleteDirectory(File path) {
        if (path == null) return;
        if (path.exists()) {
            for(File f : Objects.requireNonNull(path.listFiles())) {
                if (f.isDirectory()) deleteDirectory(f);
                f.delete();
            }
            boolean delete = path.delete();
        }
    }

    /***
     * Get Process CPU Load
     * @return
     */
    public static double getProcessCpuLoad() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            
            // Get system load average
            double cpuLoad = osBean.getSystemLoadAverage();
            
            // Convert to percentage with 1 decimal point precision
            if (cpuLoad >= 0) {
                return ((int) (cpuLoad * 1000)) / 10.0;
            } else {
                return 0.0; // Return 0 instead of NaN for better display
            }
        } catch (Exception e) {
            System.out.println("Error getting CPU load: " + e.getMessage());
            return 0.0; // Return 0 instead of NaN for better display
        }
    }

    /***
     * Create database and table if required
     * @param db
     * @param table
     */
    static public void createDatabaseAndTableIfNotExists(String db, String table) {
        if (!Serengeti.storage.databaseExists(db))
            Serengeti.storage.createDatabase(db, true);
        if (!Serengeti.storage.tableExists(db, table))
            Serengeti.storage.createTable(db, table, true);
    }

}
