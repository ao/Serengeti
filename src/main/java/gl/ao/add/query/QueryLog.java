package gl.ao.add.query;

import java.util.Map;

/***
 * Send operations (queries) to the log once they have successfully run
 * This will keep track of everything that has changed and distribute the data over the network
 * So that it is available elsewhere, with replication as well
 */

public class QueryLog {

    public static void localAppend(String jsonString) {
        System.out.println(jsonString);

        /*

        new JSONObject().put("type", "createBlankShardPiece").put("table", table).put("db", db).toString()

        new JSONObject().put("type", "dropTable").put("table", table).put("db", db).toString()

        new JSONObject().put("type", "createTable").put("table", table).put("db", db).toString()

        new JSONObject().put("type", "dropTable").put("db", db).toString()

        new JSONObject().put("type", "createDatabase").put("db", db).toString()

        new JSONObject().put("type", "delete").put("db", db).put("table", table).toString()

        new JSONObject().put("type", "update").put("update_key", update_key).put("update_val", update_val).put("where_col", where_col).put("where_val", where_val).put("db", db).put("table", table).toString()

        new JSONObject().put("type", "insert").put("db", db).put("table", table).put("json", json).toString()



        */


    }

    public void append() {
        //
    }

}
