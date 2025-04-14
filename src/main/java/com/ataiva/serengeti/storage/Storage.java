package com.ataiva.serengeti.storage;

import com.ataiva.serengeti.Serengeti;
import com.ataiva.serengeti.query.QueryLog;
import com.ataiva.serengeti.schema.DatabaseObject;
import com.ataiva.serengeti.helpers.Globals;
import com.ataiva.serengeti.schema.TableReplicaObject;
import com.ataiva.serengeti.schema.TableStorageObject;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Storage {

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
    public List<String> getDatabases() {
        return getDatabases(false);
    }

    /***
     * Get Databases
     * @param getFromFileSystem
     * @return
     */
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
    public List<String> select(String db, String table, String selectWhat, String col, String val) {
        try {

//            Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
//            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            DatabaseObject dbo = databases.get(db);

            if (tableExists(db, table)) {
                List<String> list = new ArrayList<>();
                Set<String> uuids = new HashSet<String>();

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
                            if ( !uuids.contains( row.getString("__uuid") ) ) {
                                uuids.add( row.getString("__uuid") ); // make sure it's always unique
                                list.add( row.toString() );
                            }
                        }
                    }
                }

                return list;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /***
     * Insert
     * @param db
     * @param table
     * @param json
     * @return StorageResponseObject
     */
    public StorageResponseObject insert(String db, String table, JSONObject json) {
        return insert(db, table, json, false);
    }
    public StorageResponseObject insert(String db, String table, JSONObject json, boolean isReplicationAction) {
        StorageResponseObject sro = new StorageResponseObject();

        try {
            if (!tableExists(db, table)) createTable(db, table);
            createTablePathIfNotExists(db, table);

            JSONObject _nodes = Serengeti.network.getPrimarySecondary();
            String _node_primary_id = _nodes.getJSONObject("primary").getString("id");
            String _node_primary_ip = _nodes.getJSONObject("primary").getString("ip");
            String _node_secondary_id = _nodes.getJSONObject("secondary").getString("id");
            String _node_secondary_ip = _nodes.getJSONObject("secondary").getString("ip");

            String row_id = UUID.randomUUID().toString();

            // store the data on `primary` and `secondary` nodes
            String _jsonInsertReplicate = new JSONObject() {{
                put("db", db);
                put("table", table);
                put("row_id", row_id);
                put("json", json);
                put("type", "ReplicateInsertObject");
            }}.toString();
            Serengeti.network.communicateQueryLogSingleNode(_node_primary_id, _node_primary_ip, _jsonInsertReplicate);
            Serengeti.network.communicateQueryLogSingleNode(_node_secondary_id, _node_secondary_ip, _jsonInsertReplicate);


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

            return sro;
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
    public boolean update(String db, String table, String update_key, String update_val, String where_col, String where_val) {
        return update(db, table, update_key, update_val, where_col, where_val, false);
    }
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

                    String _node_primary_id = _nodes.getString("primary");
                    String _node_primary_ip = Serengeti.network.getIPFromUUID(_node_primary_id);
                    String _node_secondary_id = _nodes.getString("secondary");
                    String _node_secondary_ip = Serengeti.network.getIPFromUUID(_node_secondary_id);

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
                    Serengeti.network.communicateQueryLogSingleNode(_node_primary_id, _node_primary_ip, _jsonUpdateReplicate);
                    Serengeti.network.communicateQueryLogSingleNode(_node_secondary_id, _node_secondary_ip, _jsonUpdateReplicate);

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
    public boolean delete(String db, String table, String where_col, String where_val) {
        return delete(db, table, where_col, where_val, false);
    }
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

                    String _node_primary_id = _nodes.getString("primary");
                    String _node_primary_ip = Serengeti.network.getIPFromUUID(_node_primary_id);
                    String _node_secondary_id = _nodes.getString("secondary");
                    String _node_secondary_ip = Serengeti.network.getIPFromUUID(_node_secondary_id);

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
                    Serengeti.network.communicateQueryLogSingleNode(_node_primary_id, _node_primary_ip, _jsonDeleteReplicate);
                    Serengeti.network.communicateQueryLogSingleNode(_node_secondary_id, _node_secondary_ip, _jsonDeleteReplicate);

                    // tell all nodes about where the replicated data is stored
                    Serengeti.network.communicateQueryLogAllNodes(new JSONObject() {{
                        put("type", "TableReplicaObjectDelete");
                        put("db", db);
                        put("table", table);
                        put("row_id", _row_id);
                    }}.toString());

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
    public boolean databaseExists(String db) {
        File f = new File(Globals.data_path + db + Globals.meta_extention);
        return f.exists();
    }

    /***
     * Create a new Database
     * @param db
     * @return boolean
     */
    public boolean createDatabase(String db) {
        return createDatabase(db, false);
    }
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
    public boolean dropDatabase(String db) {
        return dropDatabase(db, false);
    }
    public boolean dropDatabase(String db, boolean isReplicationAction) {
        try {
            Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
            boolean deleted = Files.deleteIfExists(file);

            File _db_dir = new File(Globals.data_path + db);
            if (_db_dir.exists()) Globals.deleteDirectory(_db_dir);

            loadMetaDatabasesToMemory();

            if (!isReplicationAction)
                QueryLog.localAppend(new JSONObject().put("type", "dropDatabase").put("db", db).toString());

            return deleted;
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
    public boolean createTable(String db, String table) {
        return createTable(db, table, false);
    }
    public boolean createTable(String db, String table, boolean isReplicationAction) {
        try {
            Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
//            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            DatabaseObject dbo = databases.get(db);

            Serengeti.storage.createTablePathIfNotExists(db, table);

            if (tableExists(db, table)) {
                return false;
            } else {
                dbo.createTable(table);

                byte[] data = dbo.returnDBObytes();
                Files.write(file, data);
                loadMetaDatabasesToMemory();
                loadAllStorageObjectsToMemory();
                loadAllReplicaObjectsToMemory();

                if (!isReplicationAction)
                    QueryLog.localAppend(new JSONObject().put("type", "createTable").put("table", table).put("db", db).toString());

                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Does a Table Exist?
     * @param db
     * @param table
     * @return boolean
     */
    public boolean tableExists(String db, String table) {
//        Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
//        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        if (databases.containsKey(db)) {
            DatabaseObject dbo = databases.get(db);
            if (dbo.tables != null && dbo.tables.size() > 0)
                return dbo.tables.contains(table);
        }
        return false;
    }

    /***
     * Drop a Table
     * @param db
     * @param table
     * @return boolean
     */
    public boolean dropTable(String db, String table) {
        return dropTable(db, table, false);
    }
    public boolean dropTable(String db, String table, boolean isReplicationAction) {
        Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
//        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        DatabaseObject dbo = databases.get(db);

        List<String> removeMe = new LinkedList<>();

        if (dbo.tables.size()>0) {
            for (int i=0; i<dbo.tables.size(); i++) {
                String t = dbo.tables.get(0).toString();
                if (t.equals(table)) {
                    // We do this to avoid a ConcurrentModificationException..
                    removeMe.add(t);
                }
            }

            if (removeMe.size()>0) {
                for (String t: removeMe) {
                    dbo.tables.remove(t);
                    deleteTablePathIfExists(db, table);

                    byte[] data = dbo.returnDBObytes();
                    try {
                        Files.write(file, data);

                        if (!isReplicationAction)
                            QueryLog.localAppend(new JSONObject().put("type", "dropTable").put("table", table).put("db", db).toString());

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        loadMetaDatabasesToMemory();
        loadAllStorageObjectsToMemory();
        loadAllReplicaObjectsToMemory();
        return true;
    }



    /***
     * Get a List of existing Tables
     * @param db
     * @return List
     */
    public List<String> getTables(String db) {
//        Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
//        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        DatabaseObject dbo = databases.get(db);
        if (dbo.tables == null) return null;
        else if (dbo.tables.size()>0) return dbo.tables;
        else return new ArrayList<String>();
    }

    /***
     * Create Table Path if not exists
     * @param db
     * @param table
     * @return boolean
     */
    public boolean createTablePathIfNotExists(String db, String table) {
        try {
            // make sure `data` directory exists
            File _data_dir = new File(Globals.data_path);
            if (!_data_dir.exists()) _data_dir.mkdir();
            // make sure `database` directory exists
            File _db_dir = new File(Globals.data_path + db + "/");
            if (!_db_dir.exists()) _db_dir.mkdir();
            // make sure `table` directory exists
            File _table_dir = new File(Globals.data_path + db + "/" + table + "/");
            if (!_table_dir.exists()) {
                _table_dir.mkdir();

                TableStorageObject tso = new TableStorageObject();
                Path _tso_file = Paths.get(Globals.data_path + db + "/" + table + "/"+ Globals.storage_filename);
                byte[] _tso_data = tso.returnDBObytes();
                Files.write(_tso_file, _tso_data);

                TableReplicaObject tro = new TableReplicaObject();
                Path _tro_file = Paths.get(Globals.data_path + db + "/" + table + "/"+ Globals.replica_filename);
                byte[] _tro_data = tro.returnDBObytes();
                Files.write(_tro_file, _tro_data);

                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
    /***
     * Create Database Path if not exists
     * @param db
     * @return boolean
     */
    public boolean createDatabasePathIfNotExists(String db) {
        try {
            // make sure `data` directory exists
            File _data_dir = new File(Globals.data_path);
            if (!_data_dir.exists()) _data_dir.mkdir();
            // make sure `database` directory exists
            File _db_dir = new File(Globals.data_path + db + "/");
            if (!_db_dir.exists()) _db_dir.mkdir();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Deleted the Table path and all table data
     * @param db
     * @param table
     * @return boolean
     */
    public boolean deleteTablePathIfExists(String db, String table) {
        try {
            // make sure `table` directory exists
            File _table_dir = new File(Globals.data_path + db + "/" + table + "/");
            if (_table_dir.exists()) {
                Globals.deleteDirectory(_table_dir);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Delete all data objects in one fell swoop
     */
    public void deleteEverything() {
        List<String> dbs = getDatabases();
        for (String db: dbs) {
            try {
                dropDatabase(db);
            } catch (Exception ignored) {}
        }
    }

}
