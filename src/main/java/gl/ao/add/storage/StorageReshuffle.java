package gl.ao.add.storage;

import gl.ao.add.ADD;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

public class StorageReshuffle {

    public void init() {}

    public void queueLostNode(JSONObject node) {
        final JSONObject json = node;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(10000);
                    boolean nodeIsOnline = ADD.network.nodeIsOnline(json.getString("ip"));
//                    String nodeKey = json.getString("")

                    if (!nodeIsOnline) {
                        // find out if we need to move any local data to another node
                        for (String tableKey: ADD.storage.tableReplicaObjects.keySet()) {
                            for (String rowKey: ADD.storage.tableReplicaObjects.get(tableKey).row_replicas.keySet()) {
                                String strRow = ADD.storage.tableReplicaObjects.get(tableKey).row_replicas.get(rowKey);
                                JSONObject jsonRow = new JSONObject(strRow);
                                JSONObject prisec = ADD.network.getPrimarySecondary();
                                String newPrimaryId = ((JSONObject)prisec.get("primary")).getString("id");
                                String newPrimaryIp = ((JSONObject)prisec.get("primary")).getString("ip");
                                String newSecondaryId = ((JSONObject)prisec.get("secondary")).getString("id");
                                String newSecondaryIp = ((JSONObject)prisec.get("secondary")).getString("ip");

                                List<String> dbAndTableList = Arrays.asList(tableKey.split("\\#"));
                                if (dbAndTableList.size() == 2) {
                                    String databaseName = dbAndTableList.get(0);
                                    String tableName = dbAndTableList.get(1);
                                    String found = "";
                                    JSONObject row_replica = null;

                                    if (jsonRow.getString("primary").equals(node.getString("id"))) {
                                        found = "primary";
                                    } else if (jsonRow.getString("secondary").equals(node.getString("id"))) {
                                        found = "secondary";
                                    }

                                    if (!found.equals("")) {
                                        JSONObject row = ADD.storage.tableStorageObjects.get(tableKey).getJsonFromRowId(rowKey);
                                        if (row!=null) {
                                            String _jsonReplaceReplicate = new JSONObject() {{
                                                put("db", databaseName);
                                                put("table", tableName);
                                                put("row_id", rowKey);
                                                put("json", row);
                                                put("type", "ReplicateInsertObject");
                                            }}.toString();

                                            if (found.equals("primary")) {
                                                ADD.network.communicateQueryLogSingleNode(newPrimaryId, newPrimaryIp, _jsonReplaceReplicate);
                                                row_replica = ADD.storage.tableReplicaObjects.get(tableKey).updateNewPrimary(rowKey, newPrimaryId);
                                            } else if (found.equals("secondary")) {
                                                ADD.network.communicateQueryLogSingleNode(newPrimaryId, newPrimaryIp, _jsonReplaceReplicate);
                                                row_replica = ADD.storage.tableReplicaObjects.get(tableKey).updateNewSecondary(rowKey, newSecondaryId);
                                            }

                                            if (row_replica != null) {
                                                final JSONObject row_replica_final = row_replica;
                                                ADD.network.communicateQueryLogAllNodes(new JSONObject() {{
                                                    put("type", "TableReplicaObjectInsertOrReplace");
                                                    put("db", databaseName);
                                                    put("table", tableName);
                                                    put("row_id", rowKey);
                                                    put("json", new JSONObject() {{
                                                        put("primary", row_replica_final.getString("primary"));
                                                        put("secondary", row_replica_final.getString("secondary"));
                                                    }}.toString());
                                                }}.toString());
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                } catch (InterruptedException ie) {}
            }
        }).start();
    }

}
