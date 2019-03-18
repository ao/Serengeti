package gl.ao.add.query;

import gl.ao.add.Construct;
import org.json.JSONObject;

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

        switch (type) {
            case "createDatabase":
                // new JSONObject().put("type", "createDatabase").put("db", db).toString()
                Construct.network.communicateQueryLog(jsonString);
                break;
            case "dropDatabase":
                // new JSONObject().put("type", "dropDatabase").put("db", db).toString()
                Construct.network.communicateQueryLog(jsonString);
                break;
            case "createTable":
                // new JSONObject().put("type", "createTable").put("table", table).put("db", db).toString()
                Construct.network.communicateQueryLog(jsonString);
                break;
            case "dropTable":
                // new JSONObject().put("type", "dropTable").put("table", table).put("db", db).toString()
                Construct.network.communicateQueryLog(jsonString);
                break;
        }

        /*

        new JSONObject().put("type", "createBlankShardPiece").put("table", table).put("db", db).toString()

        new JSONObject().put("type", "delete").put("db", db).put("table", table).toString()

        new JSONObject().put("type", "update").put("update_key", update_key).put("update_val", update_val).put("where_col", where_col).put("where_val", where_val).put("db", db).put("table", table).toString()

        new JSONObject().put("type", "insert").put("db", db).put("table", table).put("json", json).toString()

        */

    }


    /***
     * A networked node has been told about a replication action
     * and now needs to perform it
     * @param jsonString
     */
    public static void performReplicationAction(String jsonString) {
        // First validate we have valid JSON

        boolean validJSON = false;
        JSONObject jsonObject = null;

        try {
            jsonObject = new JSONObject(jsonString);
            validJSON = true;
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (validJSON) {
            String type = jsonObject.getString("type");

            boolean isReplicationAction = true;

            switch (type) {
                case "createDatabase":
                    Construct.storage.createDatabase(jsonObject.get("db").toString(), isReplicationAction);
                    break;
                case "dropDatabase":
                    Construct.storage.dropDatabase(jsonObject.get("db").toString(), isReplicationAction);
                    break;
                case "createTable":
                    Construct.storage.createTable(jsonObject.get("db").toString(), jsonObject.get("table").toString(), isReplicationAction);
                    break;
                case "dropTable":
                    Construct.storage.dropTable(jsonObject.get("db").toString(), jsonObject.get("table").toString(), isReplicationAction);
                    break;
            }
        }

    }

    public void append() {
        //
    }

}
