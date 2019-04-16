package gl.ao.add.query;

import gl.ao.add.ADD;
import gl.ao.add.schema.TableReplicaObject;
import gl.ao.add.schema.TableStorageObject;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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

            /**
             * Communicate to at least one other node in order to replicate data
             */
            case "insert":
                // {"type":"insert", "db":db, "table":table, "json":json}
                if (totalAvailableNodes>1) { //replicas available?
                    JSONObject randomNode = ADD.network.getRandomAvailableNode();

                    String originalRowId = jsonObject.getString("rowId");
                    String originalPieceId = jsonObject.getString("pieceId");
                    jsonObject.remove("rowId"); //make sure this is not sent to the replica
                    jsonObject.remove("pieceId"); //make sure this is not sent to the replica

                    String replicateToIP = randomNode.getString("ip");
                    String replicateToID = randomNode.getString("id");
                    String db = jsonObject.getString("db");
                    String table = jsonObject.getString("table");

                    JSONObject jsonStringTmp = jsonObject;
                    jsonStringTmp.put("__replica", ADD.server.server_constants.id); //tell the copy about where the original data was inserted

                    ADD.network.communicateQueryLogSingleNode(replicateToID, replicateToIP, jsonStringTmp.toString());
//                    ADD.storage.updateReplicaByRowId(db, table, originalPieceId, originalRowId, replicateToID);
                }

                break;
            case "update":
                // {"type":"update", "db":db, "table":table, "update_key":update_key, "update_val":update_val, "where_col":where_col, "where_val":where_val}
                break;
            case "delete":
                // {"type":"delete", "db":db, "table":table}
                break;
            case "createBlankShardPiece":
                // {"type":"createBlankShardPiece", "db":db, "table":table}
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

                    case "TableReplicaObjectInsert":
                        tro = new TableReplicaObject(db, table);
                        JSONObject _json = new JSONObject( jsonObject.getString("json") );
                        tro.insert(jsonObject.getString("row_id"), new JSONObject() {{
                            put("primary", _json.getString("primary") );
                            put("secondary", _json.getString("secondary") );
                        }});
                        tro.saveToDisk();
                        break;
                    case "TableReplicaObjectDelete":
                        tro = new TableReplicaObject(db, table);
                        tro.delete(jsonObject.getString("row_id"));
                        tro.saveToDisk();
                        break;
                    case "ReplicateInsertObject":
                        tso = new TableStorageObject(db, table);
                        tso.insert(jsonObject.getString("row_id"), (JSONObject) jsonObject.get("json"));
                        tso.saveToDisk();
                        break;
                    case "ReplicateUpdateObject":
                        tso = new TableStorageObject(db, table);
                        JSONObject __json1 = tso.getJsonFromRowId( jsonObject.getString("row_id") );
                        Iterator<String> keys1 = __json1.keys();

                        while(keys1.hasNext()) {
                            String key = keys1.next();
                            JSONObject ___json1 = jsonObject.getJSONObject("json");

                            if (key.equals( ___json1.getString("where_col") ) && __json1.get(key).equals( ___json1.getString("where_val") )) {
                                __json1.remove( ___json1.getString("where_col") );
                                __json1.put( ___json1.getString("update_key"), ___json1.getString("update_val") );
                            }
                        }
                        tso.update( jsonObject.getString("row_id") , __json1);
                        tso.saveToDisk();
                        break;
                    case "ReplicateDeleteObject":
                        tso = new TableStorageObject(db, table);
                        tso.delete( jsonObject.getString("row_id") );
//                        JSONObject __json2 = tso.getJsonFromRowId( jsonObject.getString("row_id") );
//                        Iterator<String> keys2 = __json2.keys();
//
//                        while(keys2.hasNext()) {
//                            String key = keys2.next();
//                            JSONObject ___json2 = jsonObject.getJSONObject("json");
//
//                            if (key.equals( ___json2.getString("where_col") ) && __json2.get(key).equals( ___json2.getString("where_val") )) {
//                                __json2.remove( ___json2.getString("where_col") );
//                            }
//                        }
//                        tso.update( jsonObject.getString("row_id") , __json2);
                        tso.saveToDisk();
                        break;
                    case "SelectRespond":
                        String col = jsonObject.getString("col");
                        String val = jsonObject.getString("val");
                        String selectWhat = jsonObject.getString("selectWhat");

                        List<String> list = new ArrayList<>();

                        tso = new TableStorageObject(db, table);
                        List jsonList = tso.select(col, val);
                        if (jsonList.size()==0) {
//                            return list;
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
