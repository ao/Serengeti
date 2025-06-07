package com.ataiva.serengeti.storage;

import org.json.JSONArray;
import org.json.JSONObject;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.query.QueryLog;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Storage implements IStorage {

    public static Map<String, DatabaseObject> databases = new HashMap<>();
    public static Map<String, TableStorageObject> tableStorageObjects = new HashMap<>();
    public static Map<String, TableReplicaObject> tableReplicaObjects = new HashMap<>();

    public Storage() {
        loadMetaDatabasesToMemory();
        loadAllStorageObjectsToMemory();
        loadAllReplicaObjectsToMemory();
    }

    /***
     * Load Meta Databases to Memory
     */
    @Override
    public void loadMetaDatabasesToMemory() {
        List<String> dbs = getDatabases(true);

        // clear the in-memory list of databases
        databases = new HashMap<>();

        for (String databaseName : dbs) {
            Path file = Paths.get(Globals.data_path + databaseName + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            databases.put(databaseName, dbo);
        }
    }

    /***
     * Load All Storage Objects to Memory
     * Requires the `databases` variable to be populated
     */
    @Override
    public void loadAllStorageObjectsToMemory() {
        for (String key: databases.keySet()) {
            String dbName = databases.get(key).name;
            List tables = getTables(dbName);
            for (Object table: tables) {
                String tableName = table.toString();
                TableStorageObject tso = new TableStorageObject(dbName, tableName);
                tableStorageObjects.put(dbName+"#"+tableName, tso);
            }
        }
    }
    /***
     * Load All Replica Objects to Memory
     * Requires the `databases` variable to be populated
     */
    @Override
    public void loadAllReplicaObjectsToMemory() {
        for (String key: databases.keySet()) {
            String dbName = databases.get(key).name;
            List tables = getTables(dbName);
            for (Object table: tables) {
                String tableName = table.toString();
                TableReplicaObject tro = new TableReplicaObject(dbName, tableName);
                tableReplicaObjects.put(dbName+"#"+tableName, tro);
            }
        }
    }

    /***
     * Get a List of existing Databases (use in-memory)
     * @return List
     */
    @Override
    public List<String> getDatabases() {
        return getDatabases(false);
    }

    /***
     * Get Databases
     * @param getFromFileSystem
     * @return
     */
    @Override
    public List<String> getDatabases(boolean getFromFileSystem) {
        File dir = new File(Globals.data_path);
        File[] files = dir.listFiles((dir1, name) -> name.endsWith(Globals.meta_extention));

        List<String> ddbs = new ArrayList<>();

        assert files != null;
        for (File ddb : files) {
            ddbs.add(ddb.getName().replace(Globals.meta_extention, ""));
        }
        return ddbs;
    }

    /***
     * Scan meta information and return a list of Databases and Tables included
     * @return
     */
    @Override
    public Map<String, List<String>> getDatabasesTablesMeta() {
        File dir = new File(Globals.data_path);
        File[] files = dir.listFiles((dir1, name) -> name.endsWith(Globals.meta_extention));

        Map<String, List<String>> ddbs = new HashMap<>();

        assert files != null;
        for (File ddb : files) {
            String dbName = ddb.getName().replace(Globals.meta_extention, "");
            List<String> tables = getTables(dbName);
            ddbs.put(dbName, tables);
        }
        return ddbs;
    }

    /***
     * Select
     * @param db
     * @param table
     * @param selectWhat
     * @param col
     * @param val
     * @return
     */
    @Override
    public List<String> select(String db, String table, String selectWhat, String col, String val) {
        try {
            DatabaseObject dbo = databases.get(db);

            if (tableExists(db, table)) {
                List<String> list = new ArrayList<>();
                Set<String> uuids = new HashSet<String>();

                try {
                    // Try to get data from the network
                    JSONArray array = Serengeti.network.communicateQueryLogAllNodes(new JSONObject() {{
                        put("type", "SelectRespond");
                        put("selectWhat", selectWhat);
                        put("db", db);
                        put("table", table);
                        put("col", col);
                        put("val", val);
                    }}.toString());

                    for (int i = 0; i < array.length(); i++) {
                        String arr = array.getString(i);
                        if (!arr.equals("") && !arr.equals("POST")) {
                            JSONArray selectList = new JSONArray(arr);

                            for (int j = 0; j < selectList.length(); j++) {
                                JSONObject row = (JSONObject) selectList.get(j);
                                if (!uuids.contains(row.getString("__uuid"))) {
                                    uuids.add(row.getString("__uuid")); // make sure it's always unique
                                    list.add(row.toString());
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // If network communication fails, try to get data from local storage
                    TableStorageObject tso = tableStorageObjects.get(db + "#" + table);
                    if (tso != null) {
                        if (col.isEmpty() && val.isEmpty()) {
                            // Return all rows
                            for (String key : tso.rows.keySet()) {
                                JSONObject json = new JSONObject(tso.rows.get(key));
                                json.put("__uuid", key);
                                list.add(json.toString());
                            }
                        } else {
                            // Return rows matching the condition
                            for (String key : tso.rows.keySet()) {
                                JSONObject json = new JSONObject(tso.rows.get(key));
                                if (json.has(col) && json.getString(col).equals(val)) {
                                    json.put("__uuid", key);
                                    list.add(json.toString());
                                }
                            }
                        }
                    }
                }

                return list;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>(); // Return empty list instead of null
    }

    /***
     * Insert
     * @param db
     * @param table
     * @param json
     * @return StorageResponseObject
     */
    @Override
    public StorageResponseObject insert(String db, String table, JSONObject json) {
        return insert(db, table, json, false);
    }
    
    @Override
    public StorageResponseObject insert(String db, String table, JSONObject json, boolean isReplicationAction) {
        StorageResponseObject sro = new StorageResponseObject();

        try {
            if (!tableExists(db, table)) createTable(db, table);
            createTablePathIfNotExists(db, table);

            try {
                JSONObject _nodes = Serengeti.network.getPrimarySecondary();
                
                // Get primary node info with safe access
                JSONObject primaryNode = _nodes.getJSONObject("primary");
                String _node_primary_id = primaryNode.optString("id", "");
                String _node_primary_ip = primaryNode.optString("ip", "127.0.0.1");
                
                // Get secondary node info with safe access
                JSONObject secondaryNode = _nodes.getJSONObject("secondary");
                String _node_secondary_id = secondaryNode.optString("id", "");
                String _node_secondary_ip = secondaryNode.optString("ip", "");
                
                String row_id = UUID.randomUUID().toString();
                
                // Store the data locally first
                TableStorageObject tso = tableStorageObjects.get(db+"#"+table);
                if (tso != null) {
                    json.put("__uuid", row_id);
                    tso.insert(row_id, json);
                }
                
                // store the data on `primary` and `secondary` nodes
                String _jsonInsertReplicate = new JSONObject() {{
                    put("db", db);
                    put("table", table);
                    put("row_id", row_id);
                    put("json", json);
                    put("type", "ReplicateInsertObject");
                }}.toString();
                
                // Always communicate with primary node if it exists
                if (!_node_primary_id.isEmpty() && !_node_primary_ip.isEmpty()) {
                    Serengeti.network.communicateQueryLogSingleNode(_node_primary_id, _node_primary_ip, _jsonInsertReplicate);
                }
                
                // Only communicate with secondary node if it exists
                if (!_node_secondary_id.isEmpty() && !_node_secondary_ip.isEmpty()) {
                    Serengeti.network.communicateQueryLogSingleNode(_node_secondary_id, _node_secondary_ip, _jsonInsertReplicate);
                }


                // tell all nodes about where the replicated data is stored
                Serengeti.network.communicateQueryLogAllNodes(new JSONObject() {{
                    put("type", "TableReplicaObjectInsertOrReplace");
                    put("db", db);
                    put("table", table);
                    put("row_id", row_id);
                    put("json", new JSONObject() {{
                        put("primary", _node_primary_id);
                        put("secondary", _node_secondary_id);
                    }}.toString());
                }}.toString());

                sro.rowId = row_id;
                sro.success = true;
                sro.primary = _node_primary_id;
                sro.secondary = _node_secondary_id;
                
                // Update indexes if the insert was successful
                if (sro.success) {
                    Serengeti.indexManager.handleInsert(db, table, row_id, json);
                }

                return sro;
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return sro;
    }

    /***
     * Update
     * @param db
     * @param table
     * @param update_key
     * @param update_val
     * @param where_col
     * @param where_val
     * @return boolean
     */
    @Override
    public boolean update(String db, String table, String update_key, String update_val, String where_col, String where_val) {
        return update(db, table, update_key, update_val, where_col, where_val, false);
    }
    
    @Override
    public boolean update(String db, String table, String update_key, String update_val, String where_col, String where_val, boolean isReplicationAction) {
        try {
            if (tableExists(db, table)) {

                List<String> selected = select(db, table, "*", where_col, where_val);
                for (String _item: selected) {
                    JSONObject __item = new JSONObject(_item);
                    String _row_id = __item.getString("__uuid");

                    TableReplicaObject tro = new TableReplicaObject(db, table);
                    // get nodes where replicas are stored
                    JSONObject _nodes = tro.getRowReplica(_row_id);

                    String _node_primary_id = _nodes.optString("primary", "");
                    String _node_primary_ip = !_node_primary_id.isEmpty() ? Serengeti.network.getIPFromUUID(_node_primary_id) : "";
                    String _node_secondary_id = _nodes.optString("secondary", "");
                    String _node_secondary_ip = !_node_secondary_id.isEmpty() ? Serengeti.network.getIPFromUUID(_node_secondary_id) : "";

                    JSONObject _json = new JSONObject() {{
                        put("update_key", update_key);
                        put("update_val", update_val);
                        put("where_col", where_col);
                        put("where_val", where_val);
                    }};

                    // update the data on `primary` and `secondary` nodes
                    String _jsonUpdateReplicate = new JSONObject() {{
                        put("db", db);
                        put("table", table);
                        put("row_id", _row_id);
                        put("json", _json);
                        put("type", "ReplicateUpdateObject");
                    }}.toString();
                    
                    // Only communicate with nodes that exist
                    if (!_node_primary_id.isEmpty() && !_node_primary_ip.isEmpty()) {
                        Serengeti.network.communicateQueryLogSingleNode(_node_primary_id, _node_primary_ip, _jsonUpdateReplicate);
                    }
                    
                    if (!_node_secondary_id.isEmpty() && !_node_secondary_ip.isEmpty()) {
                        Serengeti.network.communicateQueryLogSingleNode(_node_secondary_id, _node_secondary_ip, _jsonUpdateReplicate);
                    }
                    
                    // Get the old JSON data for indexing
                    TableStorageObject tso = tableStorageObjects.get(db+"#"+table);
                    if (tso != null) {
                        JSONObject oldJson = tso.getJsonFromRowId(_row_id);
                        if (oldJson != null) {
                            // Create the new JSON with the updated value
                            JSONObject newJson = new JSONObject(oldJson.toString());
                            newJson.put(update_key, update_val);
                            
                            // Update the indexes
                            Serengeti.indexManager.handleUpdate(db, table, _row_id, oldJson, newJson);
                        }
                    }

                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Update
     * @param db
     * @param table
     * @param where_col
     * @param where_val
     * @return boolean
     */
    @Override
    public boolean delete(String db, String table, String where_col, String where_val) {
        return delete(db, table, where_col, where_val, false);
    }
    
    @Override
    public boolean delete(String db, String table, String where_col, String where_val, boolean isReplicationAction) {
        try {

            if (tableExists(db, table)) {

                List<String> selected = select(db, table, "*", where_col, where_val);
                for (String _item: selected) {
                    JSONObject __item = new JSONObject(_item);
                    String _row_id = __item.getString("__uuid");

                    TableReplicaObject tro = new TableReplicaObject(db, table);
                    // get nodes where replicas are stored
                    JSONObject _nodes = tro.getRowReplica(_row_id);

                    String _node_primary_id = _nodes.optString("primary", "");
                    String _node_primary_ip = !_node_primary_id.isEmpty() ? Serengeti.network.getIPFromUUID(_node_primary_id) : "";
                    String _node_secondary_id = _nodes.optString("secondary", "");
                    String _node_secondary_ip = !_node_secondary_id.isEmpty() ? Serengeti.network.getIPFromUUID(_node_secondary_id) : "";

                    JSONObject _json = new JSONObject() {{
                        put("where_col", where_col);
                        put("where_val", where_val);
                    }};

                    // update the data on `primary` and `secondary` nodes
                    String _jsonDeleteReplicate = new JSONObject() {{
                        put("db", db);
                        put("table", table);
                        put("row_id", _row_id);
                        put("json", _json);
                        put("type", "ReplicateDeleteObject");
                    }}.toString();
                    
                    // Only communicate with nodes that exist
                    if (!_node_primary_id.isEmpty() && !_node_primary_ip.isEmpty()) {
                        Serengeti.network.communicateQueryLogSingleNode(_node_primary_id, _node_primary_ip, _jsonDeleteReplicate);
                    }
                    
                    if (!_node_secondary_id.isEmpty() && !_node_secondary_ip.isEmpty()) {
                        Serengeti.network.communicateQueryLogSingleNode(_node_secondary_id, _node_secondary_ip, _jsonDeleteReplicate);
                    }

                    // tell all nodes about where the replicated data is stored
                    Serengeti.network.communicateQueryLogAllNodes(new JSONObject() {{
                        put("type", "TableReplicaObjectDelete");
                        put("db", db);
                        put("table", table);
                        put("row_id", _row_id);
                    }}.toString());
                    
                    // Get the JSON data for indexing
                    TableStorageObject tso = tableStorageObjects.get(db+"#"+table);
                    if (tso != null) {
                        JSONObject json = tso.getJsonFromRowId(_row_id);
                        if (json != null) {
                            // Update the indexes
                            Serengeti.indexManager.handleDelete(db, table, _row_id, json);
                        }
                    }

                    return true;
                }


            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }


    /***
     * Does Database Exist?
     * @param db
     * @return boolean
     */
    @Override
    public boolean databaseExists(String db) {
        File f = new File(Globals.data_path + db + Globals.meta_extention);
        return f.exists();
    }

    /***
     * Create a new Database
     * @param db
     * @return boolean
     */
    @Override
    public boolean createDatabase(String db) {
        return createDatabase(db, false);
    }
    
    @Override
    public boolean createDatabase(String db, boolean isReplicationAction) {
        try {
            DatabaseObject dbo = new DatabaseObject();
            dbo.createNew(db, null);
            byte[] data = dbo.returnDBObytes();
            Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
            Files.write(file, data);

            //create directory path if not exists
            Serengeti.storage.createDatabasePathIfNotExists(db);

            loadMetaDatabasesToMemory();

            if (!isReplicationAction)

                QueryLog.localAppend(new JSONObject().put("type", "createDatabase").put("db", db).toString());

            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Drop an existing Database
     * @param db
     * @return boolean
     */
    @Override
    public boolean dropDatabase(String db) {
        return dropDatabase(db, false);
    }
    
    public boolean dropDatabase(String db, boolean isReplicationAction) {
        try {
            if (databaseExists(db)) {
                File f = new File(Globals.data_path + db + Globals.meta_extention);
                f.delete();

                File dir = new File(Globals.data_path + db);
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    for (File file : files) {
                        file.delete();
                    }
                    dir.delete();
                }

                loadMetaDatabasesToMemory();

                if (!isReplicationAction)
                    QueryLog.localAppend(new JSONObject().put("type", "dropDatabase").put("db", db).toString());

                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Create a new Table
     * @param db
     * @param table
     * @return boolean
     */
    @Override
    public boolean createTable(String db, String table) {
        return createTable(db, table, false);
    }
    
    @Override
    public boolean createTable(String db, String table, boolean isReplicationAction) {
        try {
            if (!databaseExists(db)) {
                createDatabase(db);
            }

            DatabaseObject dbo = databases.get(db);
            if (dbo != null) {
                if (!dbo.tables.contains(table)) {
                    dbo.tables.add(table);
                    byte[] data = dbo.returnDBObytes();
                    Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
                    Files.write(file, data);

                    //create directory path if not exists
                    createTablePathIfNotExists(db, table);

                    loadMetaDatabasesToMemory();
                    loadAllStorageObjectsToMemory();
                    loadAllReplicaObjectsToMemory();

                    if (!isReplicationAction)
                        QueryLog.localAppend(new JSONObject().put("type", "createTable").put("db", db).put("table", table).toString());

                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Does Table Exist?
     * @param db
     * @param table
     * @return boolean
     */
    @Override
    public boolean tableExists(String db, String table) {
        if (databaseExists(db)) {
            DatabaseObject dbo = databases.get(db);
            if (dbo != null) {
                return dbo.tables.contains(table);
            }
        }
        return false;
    }

    /***
     * Drop an existing Table
     * @param db
     * @param table
     * @return boolean
     */
    public boolean dropTable(String db, String table) {
        return dropTable(db, table, false);
    }
    
    public boolean dropTable(String db, String table, boolean isReplicationAction) {
        try {
            if (tableExists(db, table)) {
                DatabaseObject dbo = databases.get(db);
                if (dbo != null) {
                    dbo.tables.remove(table);
                    byte[] data = dbo.returnDBObytes();
                    Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
                    Files.write(file, data);

                    //delete directory path if exists
                    deleteTablePathIfExists(db, table);

                    loadMetaDatabasesToMemory();
                    loadAllStorageObjectsToMemory();
                    loadAllReplicaObjectsToMemory();

                    if (!isReplicationAction)
                        QueryLog.localAppend(new JSONObject().put("type", "dropTable").put("db", db).put("table", table).toString());

                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Get a List of Tables in a Database
     * @param db
     * @return List
     */
    @Override
    public List<String> getTables(String db) {
        if (databaseExists(db)) {
            DatabaseObject dbo = databases.get(db);
            if (dbo != null) {
                return dbo.tables;
            }
        }
        return new ArrayList<>();
    }

    /***
     * Create Table Path If Not Exists
     * @param db
     * @param table
     * @return boolean
     */
    public boolean createTablePathIfNotExists(String db, String table) {
        try {
            File dir = new File(Globals.data_path + db);
            if (!dir.exists()) {
                dir.mkdir();
            }

            File tableDir = new File(Globals.data_path + db + "/" + table);
            if (!tableDir.exists()) {
                tableDir.mkdir();
            }

            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Create Database Path If Not Exists
     * @param db
     * @return boolean
     */
    public boolean createDatabasePathIfNotExists(String db) {
        try {
            File dir = new File(Globals.data_path + db);
            if (!dir.exists()) {
                dir.mkdir();
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Delete Table Path If Exists
     * @param db
     * @param table
     * @return boolean
     */
    public boolean deleteTablePathIfExists(String db, String table) {
        try {
            File tableDir = new File(Globals.data_path + db + "/" + table);
            if (tableDir.exists() && tableDir.isDirectory()) {
                File[] files = tableDir.listFiles();
                for (File file : files) {
                    file.delete();
                }
                tableDir.delete();
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Delete Everything
     */
    @Override
    public void deleteEverything() {
        databases = new HashMap<>();
        tableStorageObjects = new HashMap<>();
        tableReplicaObjects = new HashMap<>();
    }
    
    /**
     * Initialize the storage system.
     */
    @Override
    public void init() {
        // Storage is initialized in the constructor
    }
    
    /**
     * Shutdown the storage system.
     */
    @Override
    public void shutdown() {
        // No specific shutdown actions needed
    }
}
