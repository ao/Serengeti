package gl.ao.add.query;

import gl.ao.add.ADD;
import gl.ao.add.helpers.Globals;
import gl.ao.add.schema.TableReplicaObject;
import gl.ao.add.schema.TableStorageObject;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/***
 * Send operations (queries) to the log once they have successfully run
 * This will keep track of everything that has changed and distribute the data over the network
 * So that it is available elsewhere, with replication as well
 */

public class QueryLog {

    /***
     * Log local actions and tell the network about it
     * @param jsonString
     */
    public static void localAppend(String jsonString) {
        JSONObject jsonObject = new JSONObject(jsonString);

        String type = jsonObject.getString("type");

        int totalAvailableNodes = ADD.network.availableNodes.size();

        switch (type) {

            /**
             * Always communicate the following to all nodes
             */
            case "createDatabase":
                // {"type":"createDatabase", "db":db}
                ADD.network.communicateQueryLogAllNodes(jsonString);
                break;
            case "dropDatabase":
                // {"type":"dropDatabase", "db":db}
                ADD.network.communicateQueryLogAllNodes(jsonString);
                break;
            case "createTable":
                // {"type":"createTable", "db":db, "table":table}
                ADD.network.communicateQueryLogAllNodes(jsonString);
                break;
            case "dropTable":
                // {"type":"dropTable", "db":db, "table":table}
                ADD.network.communicateQueryLogAllNodes(jsonString);
                break;
        }
    }


    /***
     * A networked node has been told about a replication action
     * and now needs to perform it
     * @param jsonString
     */
    public static String performReplicationAction(String jsonString) {
        // First validate we have valid JSON
        JSONObject jsonObject;
        String response = "";

        try {
            jsonObject = new JSONObject(jsonString);
            if (jsonObject.has("type")) {
                String type = jsonObject.getString("type");

                String db = jsonObject.has("db") ? jsonObject.getString("db") : null;
                String table = jsonObject.has("table") ? jsonObject.getString("table") : null;

                TableReplicaObject tro;
                TableStorageObject tso;

                switch (type) {
                    case "createDatabase":
                        ADD.storage.createDatabase(db, true);
                        break;
                    case "dropDatabase":
                        ADD.storage.dropDatabase(db, true);
                        break;
                    case "createTable":
                        ADD.storage.createTable(db, table, true);
                        break;
                    case "dropTable":
                        ADD.storage.dropTable(db, table, true);
                        break;
                    case "insert":
                        ADD.storage.insert(db, table, jsonObject, true);
                        break;

                    case "DeleteEverything":
                        ADD.storage.deleteEverything();
                        break;

                    case "TableReplicaObjectInsertOrReplace":
                        Globals.createDatabaseAndTableIfNotExists(db, table);
                        JSONObject _json = new JSONObject( jsonObject.getString("json") );
                        ADD.storage.tableReplicaObjects.get(db+"#"+table).insertOrReplace(jsonObject.getString("row_id"), new JSONObject() {{
                            put("primary", _json.getString("primary") );
                            put("secondary", _json.getString("secondary") );
                        }});
                        break;
                    case "TableReplicaObjectDelete":
                        Globals.createDatabaseAndTableIfNotExists(db, table);
                        ADD.storage.tableReplicaObjects.get(db+"#"+table).delete(jsonObject.getString("row_id"));
                        break;
                    case "ReplicateInsertObject":
                        Globals.createDatabaseAndTableIfNotExists(db, table);
                        ADD.storage.tableStorageObjects.get(db+"#"+table).insert(jsonObject.getString("row_id"), (JSONObject) jsonObject.get("json"));
                        break;
                    case "ReplicateUpdateObject":
                        Globals.createDatabaseAndTableIfNotExists(db, table);
                        JSONObject __json1 = ADD.storage.tableStorageObjects.get(db+"#"+table).getJsonFromRowId( jsonObject.getString("row_id") );
                        if (__json1!=null) {
                            Iterator<String> keys1 = __json1.keys();

                            while (keys1.hasNext()) {
                                String key = keys1.next();
                                JSONObject ___json1 = jsonObject.getJSONObject("json");

                                if (key.equals(___json1.getString("where_col")) && __json1.get(key).equals(___json1.getString("where_val"))) {
                                    __json1.remove(___json1.getString("where_col"));
                                    __json1.put(___json1.getString("update_key"), ___json1.getString("update_val"));
                                }
                            }
                            ADD.storage.tableStorageObjects.get(db + "#" + table).update(jsonObject.getString("row_id"), __json1);
                        }
                        break;
                    case "ReplicateDeleteObject":
                        Globals.createDatabaseAndTableIfNotExists(db, table);
                        ADD.storage.tableStorageObjects.get(db+"#"+table).delete( jsonObject.getString("row_id") );
                        break;
                    case "SelectRespond":
                        Globals.createDatabaseAndTableIfNotExists(db, table);

                        String col = jsonObject.getString("col");
                        String val = jsonObject.getString("val");
                        String selectWhat = jsonObject.getString("selectWhat");

                        List<String> list = new ArrayList<>();

                        List jsonList = ADD.storage.tableStorageObjects.get(db+"#"+table).select(col, val);
                        if (jsonList.size()==0) {
                            // return list;
                        } else if (jsonList.size()==1) {
                            JSONObject ___json = new JSONObject(jsonList.get(0).toString());
                            if (___json!=null) {
                                if (selectWhat.equals("*")) {
                                    list.add(___json.toString());
                                } else {
                                    list.add(___json.getString(selectWhat));
                                }
                            }
                        } else {
                            for (int i=0; i<jsonList.size(); i++) {
                                JSONObject __json = new JSONObject(jsonList.get(i).toString());
                                if (__json!=null) {
                                    if (selectWhat.equals("*")) {
                                        list.add(__json.toString());
                                    } else {
                                        list.add(__json.getString(selectWhat));
                                    }
                                }
                            }
                        }

                        response = list.toString();

                        break;
                    case "SendTableReplicaToNode":
                        String node_id = jsonObject.getString("node_id");
                        String node_ip = jsonObject.getString("node_ip");
                        Map<String, String> tableReplicaRows = ADD.storage.tableReplicaObjects.get(db+"#"+table).row_replicas;
                        /*ADD.network.communicateQueryLogSingleNode(node_id, node_ip, new JSONObject(){{
                            put("type", "ReceiveTableReplicaFromNode");
                            put("db", db);
                            put("table", table);
                            put("rows", tableReplicaRows);
                        }}.toString());*/

                        try {
                            response = new JSONObject(tableReplicaRows).toString();
                        } catch (Exception e) {
                            // this will raise if the tableReplicaRows is blank..
                        }

                        break;
                    case "ReceiveTableReplicaFromNode":
                        Globals.createDatabaseAndTableIfNotExists(db, table);
                        Map<String, String> row_replicas = (Map<String, String>) jsonObject.get("rows");
                        for (String rowKey: row_replicas.keySet()) {
                            JSONObject json = new JSONObject(row_replicas.get(rowKey));
                            ADD.storage.tableReplicaObjects.get(db+"#"+table).insertOrReplace(rowKey, json);
                        }
                        break;
                }
            } else {
                System.out.println("Something might have gone wrong trying to performReplicationAction: [missing `type`] "+jsonString);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return response;

    }

}
