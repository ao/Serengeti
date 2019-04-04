package gl.ao.add.query;

import gl.ao.add.Construct;
import org.json.JSONObject;
import org.json.JSONString;

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

        int totalAvailableNodes = Construct.network.availableNodes.size();

        switch (type) {

            /**
             * Always communicate the following to all nodes
             */
            case "createDatabase":
                // {"type":"createDatabase", "db":db}
                Construct.network.communicateQueryLogAllNodes(jsonString);
                break;
            case "dropDatabase":
                // {"type":"dropDatabase", "db":db}
                Construct.network.communicateQueryLogAllNodes(jsonString);
                break;
            case "createTable":
                // {"type":"createTable", "db":db, "table":table}
                Construct.network.communicateQueryLogAllNodes(jsonString);
                break;
            case "dropTable":
                // {"type":"dropTable", "db":db, "table":table}
                Construct.network.communicateQueryLogAllNodes(jsonString);
                break;

            /**
             * Communicate to at least one other node in order to replicate data
             */
            case "insert":
                // {"type":"insert", "db":db, "table":table, "json":json}
                if (totalAvailableNodes>1) { //replicas available?
                    JSONObject randomNode = Construct.network.getRandomAvailableNode();

                    String originalRowId = jsonObject.getString("rowId");
                    String originalPieceId = jsonObject.getString("pieceId");
                    jsonObject.remove("rowId"); //make sure this is not sent to the replica
                    jsonObject.remove("pieceId"); //make sure this is not sent to the replica

                    String replicateToIP = randomNode.getString("ip");
                    String replicateToID = randomNode.getString("id");
                    String db = jsonObject.getString("db");
                    String table = jsonObject.getString("table");

                    JSONObject jsonStringTmp = jsonObject;
                    jsonStringTmp.put("__replica", Construct.server.server_constants.id); //tell the copy about where the original data was inserted

                    Construct.network.communicateQueryLogSingleNode(replicateToID, replicateToIP, jsonStringTmp.toString());
                    Construct.storage.updateReplicaByRowId(db, table, originalPieceId, originalRowId, replicateToID);
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
                        Construct.storage.createDatabase(db, true);
                        break;
                    case "dropDatabase":
                        Construct.storage.dropDatabase(db, true);
                        break;
                    case "createTable":
                        Construct.storage.createTable(db, table, true);
                        break;
                    case "dropTable":
                        Construct.storage.dropTable(db, table, true);
                        break;
                    case "insert":
                        Construct.storage.insert(db, table, jsonObject, true);
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
