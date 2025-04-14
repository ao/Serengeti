package ms.ao.serengeti.server;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ms.ao.serengeti.Serengeti;
import ms.ao.serengeti.network.Network;
import ms.ao.serengeti.ui.Dashboard;
import ms.ao.serengeti.ui.Interactive;
import ms.ao.serengeti.helpers.Globals;
import ms.ao.serengeti.query.QueryEngine;
import ms.ao.serengeti.query.QueryLog;
import org.json.JSONArray;
import org.json.JSONObject;

public class Server {

    public ServerConstants server_constants = null;
    private final String server_constants_file_location = Globals.data_path + "server.constants";
    private Path server_constants_file = null;

    public void init() {

        try {
            File root_directory = new File(Globals.data_path);
            if (! root_directory.exists()) {
                boolean mkdir = root_directory.mkdir();
            }

            server_constants_file = Paths.get(server_constants_file_location);
            if (Files.exists(server_constants_file)) {
                server_constants = (ServerConstants) Globals.convertFromBytes(Files.readAllBytes(server_constants_file));
            } else {
                server_constants = new ServerConstants();
                server_constants.id = UUID.randomUUID().toString();
                saveServerConstants();
            }
        } catch (Exception ignore) {}
    }

    private void saveServerConstants() {
        byte[] data = Globals.convertToBytes(server_constants);

        try {
            Files.write(server_constants_file, data);
        } catch (Exception ignore) {}
    }

    public void serve() {
        try {

            final Executor cachedThreadPool = Executors.newCachedThreadPool();
            final HttpServer server = HttpServer.create(new InetSocketAddress(Globals.port_default), 5);
            server.createContext("/", new RootHandler());
            server.createContext("/dashboard", new DashboardHandler());
            server.createContext("/interactive", new InteractiveHandler());
            server.createContext("/meta", new MetaHandler());
            server.createContext("/post", new GenericPostHandler());
            server.setExecutor(cachedThreadPool);
            server.start();

        } catch (BindException be) {
            System.out.println("Bind Exception: "+be.getMessage());
            System.exit(-1);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            System.out.printf("\nHTTP server started at http://%s:%d/%n",
                    Globals.getHost4Address(), Globals.port_default);
            System.out.printf("Dashboard available at http://%s:%d/dashboard%n",
                    Globals.getHost4Address(), Globals.port_default);
            System.out.printf("\nNode is 'online' and ready to contribute (took %dms to startup)%n",
                    System.currentTimeMillis() - Serengeti.startTime);
        } catch (SocketException se) {
            System.out.println("Could not start HTTP server started, IP lookup failed");
        }
    }

    static InetAddress getMyIP() throws IOException {
        return InetAddress.getLocalHost();
    }

    private static ByteArrayOutputStream requestBodyToByteArray(HttpExchange t) throws IOException {
        InputStream inputStream = t.getRequestBody();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int read = 0;
        while ((read = inputStream.read(buffer)) != -1) {
            byteArrayOutputStream.write(buffer, 0, read);
        }
        return byteArrayOutputStream;
    }

    static class RootHandler implements HttpHandler {

        /***
         * handle requests to root /
         * @param t
         * @throws IOException
         */
        public void handle(HttpExchange t) throws IOException {

            JSONObject jsonObjThis = new JSONObject();

            jsonObjThis.put("version", "0.0.1");
            jsonObjThis.put("started", Serengeti.currentDate);

            jsonObjThis.put("id", Serengeti.server.server_constants.id);

            DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
            dateFormat.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
            String formattedDate=dateFormat. format( new Date().getTime() - Serengeti.currentDate.getTime() );
            jsonObjThis.put("uptime", formattedDate);

            jsonObjThis.put("ip", Globals.getHost4Address());

            //disk
            JSONObject jsonObjectThisDisk = new JSONObject();
            try {
                File f = new File("/");
                jsonObjectThisDisk.put("free", f.getFreeSpace());
                jsonObjectThisDisk.put("free_human", f.getFreeSpace()/1024/1024/1024+"G");
                jsonObjectThisDisk.put("usable", f.getUsableSpace());
                jsonObjectThisDisk.put("usable_human", f.getUsableSpace()/1024/1024/1024+"G");
                jsonObjectThisDisk.put("total", f.getTotalSpace());
                jsonObjectThisDisk.put("total_human", f.getTotalSpace()/1024/1024/1024+"G");
            } catch (Exception e) {
                e.printStackTrace();
            }
            jsonObjThis.put("disk", jsonObjectThisDisk);

            //processors
            JSONObject jsonObjectThisCPU = new JSONObject();
            jsonObjectThisCPU.put("processors", Runtime.getRuntime().availableProcessors());
            jsonObjectThisCPU.put("load", Globals.getProcessCpuLoad());
            jsonObjThis.put("cpu", jsonObjectThisCPU);

            //memory
            JSONObject jsonObjectThisMemory = new JSONObject();
            try {
                long memorySize = ((com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean()).getTotalPhysicalMemorySize();
                long freeMemorySize = ((com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean()).getFreePhysicalMemorySize();
                jsonObjectThisMemory.put("total", memorySize);
                jsonObjectThisMemory.put("total_human", memorySize/1024/1024/1024+"G");
                jsonObjectThisMemory.put("free", freeMemorySize);
                jsonObjectThisMemory.put("free_human", freeMemorySize/1024/1024/1024+"G");
            } catch (Exception e) {
                e.printStackTrace();
            }
            jsonObjThis.put("memory", jsonObjectThisMemory);

            //os
            JSONObject jsonObjectThisOS = new JSONObject();
            try {
                String OSName = ((com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean()).getName();
                String OSVersion = ((com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean()).getVersion();
                String OSArch = ((com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean()).getArch();
                jsonObjectThisOS.put("name", OSName);
                jsonObjectThisOS.put("version", OSVersion);
                jsonObjectThisOS.put("arch", OSArch);
            } catch (Exception e) {
                e.printStackTrace();
            }
            jsonObjThis.put("os", jsonObjectThisOS);


            JSONObject jsonObjRoot = new JSONObject();
            jsonObjRoot.put("_", "Serengeti - The Autonomous Distributed Database");
            jsonObjRoot.put("this", jsonObjThis);
            jsonObjRoot.put("totalNodes", Integer.toString(Serengeti.network.availableNodes.size()));
            jsonObjRoot.put("availableNodes", Serengeti.network.availableNodes);
            jsonObjRoot.put("discoveryLatency", Network.latency);
            jsonObjRoot.put("replicationLatency", 0);

            //response
            String response = jsonObjRoot.toString();
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    static class InteractiveHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String response = null;
            try {
                response = new Interactive().IndexTemplate("http://"+t.getRequestHeaders().getFirst("Host"),
                        t.getRequestURI().getPath());
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
            t.sendResponseHeaders(200, Objects.requireNonNull(response).length());
            OutputStream os = t.getResponseBody();
            os.write(Objects.requireNonNull(response).getBytes());
            os.close();
        }
    }
    static class DashboardHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String response = null;
            try {
                response = new Dashboard()
                        .IndexTemplate(
                            "http://"+t.getRequestHeaders().getFirst("Host"),
                            t.getRequestURI().getPath()
                        );
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
            t.sendResponseHeaders(200, Objects.requireNonNull(response).length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    static class MetaHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            JSONObject jsonObject = new JSONObject();

            jsonObject.put("meta", Serengeti.storage.getDatabasesTablesMeta());

            String response = jsonObject.toString();
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }

    static void GenericMethodHandlerFailure(String expected, int statusCode, HttpExchange t) {
        try {
            String response = "Got "+t.getRequestMethod()+", expected "+expected;
            t.sendResponseHeaders(statusCode, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static class GenericPostHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String expected = "POST";
            if (t.getRequestMethod().equals(expected)) {

                String response = "POST";

                if (t.getRequestURI().toString().startsWith("/post?query")) {
                    //interactive!
                    ByteArrayOutputStream byteArrayOutputStream = requestBodyToByteArray(t);

                    JSONObject jsonObj = null;
                    try {
                        jsonObj = new JSONObject(byteArrayOutputStream.toString());
                        List<JSONObject> list = QueryEngine.query((String) jsonObj.get("value"));
                        JSONArray jsonArray = new JSONArray(list);
                        response = jsonArray.toString();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                } else if (t.getRequestURI().toString().startsWith("/post")) {
                    InputStreamReader isr =  new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8);
                    BufferedReader br = new BufferedReader(isr);

                    int b;
                    StringBuilder buf = new StringBuilder(512);
                    while ((b = br.read()) != -1) {
                        buf.append((char) b);
                    }

                    br.close();
                    isr.close();

                    response = QueryLog.performReplicationAction(buf.toString());
                }

                t.sendResponseHeaders(200, response.length());
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();
            } else {
                Server.GenericMethodHandlerFailure(expected, 400, t);
            }
        }
    }

    static Map<String, String> getParameters(HttpExchange httpExchange) {
        Map<String, String> parameters = new HashMap<>();
        try {
            ByteArrayOutputStream byteArrayOutputStream = requestBodyToByteArray(httpExchange);
            String[] keyValuePairs = byteArrayOutputStream.toString().split("&");
            for (String keyValuePair : keyValuePairs) {
                String[] keyValue = keyValuePair.split("=");
                if (keyValue.length != 2) {
                    continue;
                }
                parameters.put(keyValue[0], keyValue[1]);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return parameters;
    }

}
