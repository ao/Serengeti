package gl.ao.serengeti.storage;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

public class StorageReshuffle {

    /**
     * Recheck if a Lost Node will come back online in 10 seconds, otherwise replicate the lost data to another node
     * @param node
     */
    public void queueLostNode(JSONObject node) {
        final JSONObject json = node;
        new Thread(() -> {
            try {
                Thread.sleep(10000);
                boolean nodeIsOnline = gl.ao.serengeti.Serengeti.network.nodeIsOnline(json.getString("ip"));

                if (!nodeIsOnline) {
                    // find out if we need to move any local data to another node
                    for (String tableKey: gl.ao.serengeti.Serengeti.storage.tableReplicaObjects.keySet()) {
                        for (String rowKey: gl.ao.serengeti.Serengeti.storage.tableReplicaObjects.get(tableKey).row_replicas.keySet()) {
                            String strRow = gl.ao.serengeti.Serengeti.storage.tableReplicaObjects.get(tableKey).row_replicas.get(rowKey);
                            JSONObject jsonRow = new JSONObject(strRow);
                            JSONObject prisec = gl.ao.serengeti.Serengeti.network.getPrimarySecondary();
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
                                    JSONObject row = gl.ao.serengeti.Serengeti.storage.tableStorageObjects.get(tableKey).getJsonFromRowId(rowKey);
                                    if (row!=null) {
                                        String _jsonReplaceReplicate = new JSONObject() {{
                                            put("db", databaseName);
                                            put("table", tableName);
                                            put("row_id", rowKey);
                                            put("json", row);
                                            put("type", "ReplicateInsertObject");
                                        }}.toString();

                                        if (found.equals("primary")) {
                                            gl.ao.serengeti.Serengeti.network.communicateQueryLogSingleNode(newPrimaryId, newPrimaryIp, _jsonReplaceReplicate);
                                            row_replica = gl.ao.serengeti.Serengeti.storage.tableReplicaObjects.get(tableKey).updateNewPrimary(rowKey, newPrimaryId);
                                        } else if (found.equals("secondary")) {
                                            gl.ao.serengeti.Serengeti.network.communicateQueryLogSingleNode(newPrimaryId, newPrimaryIp, _jsonReplaceReplicate);
                                            row_replica = gl.ao.serengeti.Serengeti.storage.tableReplicaObjects.get(tableKey).updateNewSecondary(rowKey, newSecondaryId);
                                        }

                                        if (row_replica != null) {
                                            final JSONObject row_replica_final = row_replica;
                                            gl.ao.serengeti.Serengeti.network.communicateQueryLogAllNodes(new JSONObject() {{
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
        }).start();
    }

}
