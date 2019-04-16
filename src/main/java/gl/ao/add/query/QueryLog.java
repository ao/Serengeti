package gl.ao.add.query;

import gl.ao.add.ADD;
import gl.ao.add.schema.TableReplicaObject;
import gl.ao.add.schema.TableStorageObject;
import org.json.JSONObject;

import java.util.Iterator;

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
    public static void performReplicationAction(String jsonString) {
        // First validate we have valid JSON
        JSONObject jsonObject;
        try {
            jsonObject = new JSONObject(jsonString);
            if (jsonObject.has("type")) {
                String type = jsonObject.getString("type");

                String db = jsonObject.has("db") ? jsonObject.getString("db") : null;
                String table = jsonObject.has("table") ? jsonObject.getString("table") : null;

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

                    case "TableReplicaObject":
                        TableReplicaObject tro = new TableReplicaObject(db, table);
                        JSONObject _json = new JSONObject( jsonObject.getString("json") );
                        tro.insert(jsonObject.getString("row_id"), new JSONObject() {{
                            put("primary", _json.getString("primary") );
                            put("secondary", _json.getString("secondary") );
                        }});
                        tro.saveToDisk();
                        break;
                    case "ReplicateInsertObject":
                        TableStorageObject tso1 = new TableStorageObject(db, table);
                        tso1.insert(jsonObject.getString("row_id"), (JSONObject) jsonObject.get("json"));
                        tso1.saveToDisk();
                        break;
                    case "ReplicateUpdateObject":
                        TableStorageObject tso2 = new TableStorageObject(db, table);
                        JSONObject __json = tso2.getJsonFromRowId( jsonObject.getString("row_id") );
                        Iterator<String> keys = __json.keys();

                        while(keys.hasNext()) {
                            String key = keys.next();
                            JSONObject ___json = jsonObject.getJSONObject("json");

                            if (key.equals( ___json.getString("where_col") ) && __json.get(key).equals( ___json.getString("where_val") )) {
                                __json.remove( ___json.getString("where_col") );
                                __json.put( ___json.getString("update_key"), ___json.getString("update_val") );
                            }
                        }
                        tso2.update( jsonObject.getString("row_id") , __json);
                        tso2.saveToDisk();
                        break;
                }
            } else {
                System.out.println("Something might have gone wrong trying to performReplicationAction: [missing `type`] "+jsonString);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}
