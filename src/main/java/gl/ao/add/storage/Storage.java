package gl.ao.add.storage;

import gl.ao.add.ADD;
import gl.ao.add.query.QueryLog;
import gl.ao.add.schema.DatabaseObject;
import gl.ao.add.helpers.Globals;
import gl.ao.add.schema.TableReplicaObject;
import gl.ao.add.schema.TableStorageObject;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class Storage {

    Map<String, DatabaseObject> databases = new HashMap<>();

    public Storage() {
        loadMetaDatabasesToMemory();
    }

    /***
     * Load Meta Databases to Memory
     */
    public void loadMetaDatabasesToMemory() {
        List<String> dbs = getDatabases();

        for (int i=0; i<dbs.size(); i++) {
            String databaseName = dbs.get(i);
            Path file = Paths.get(Globals.data_path + databaseName + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            databases.put(databaseName, dbo);
        }
    }

    /***
     * Get a List of existing Databases
     * @return List
     */
    public List getDatabases() {
        File dir = new File(Globals.data_path);
        File[] files = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(Globals.meta_extention);
            }
        });

        List<String> ddbs = new ArrayList<>();

        for (File ddb : files) {
            ddbs.add(ddb.getName().replace(Globals.meta_extention, ""));
        }
        return ddbs;
    }

    /***
     * Scan meta information and return a list of Databases and Tables included
     * @return
     */
    public Map getDatabasesTablesMeta() {
        File dir = new File(Globals.data_path);
        File[] files = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(Globals.meta_extention);
            }
        });

        Map<String, List> ddbs = new HashMap<>();

        for (File ddb : files) {
            String dbName = ddb.getName().replace(Globals.meta_extention, "");
            List tables = getTables(dbName);
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
            Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            if (tableExists(db, table)) {
                List<String> list = new ArrayList<>();

                TableStorageObject tso = new TableStorageObject(db, table);
                List jsonList = tso.select(col, val);
                if (jsonList.size()==0) {
                    return list;
                } else if (jsonList.size()==1) {
                    JSONObject _json = new JSONObject(jsonList.get(0).toString());
                    if (_json!=null) {
                        if (selectWhat.equals("*")) {
                            list.add(_json.toString());
                        } else {
                            list.add(_json.getString(selectWhat));
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

            JSONObject _nodes = ADD.network.getPrimarySecondary();
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
            boolean success_primary_insert = ADD.network.communicateQueryLogSingleNode(_node_primary_id, _node_primary_ip, _jsonInsertReplicate);
            boolean success_secondary_insert = ADD.network.communicateQueryLogSingleNode(_node_secondary_id, _node_secondary_ip, _jsonInsertReplicate);

            // tell all nodes about where the replicated data is stored
            ADD.network.communicateQueryLogAllNodes(new JSONObject() {{
                put("type", "TableReplicaObject");
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
            Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            if (tableExists(db, table)) {

//                TableStorageObject tso = new TableStorageObject(db, table);
//                boolean updated = tso.update(update_key, update_val, where_col, where_val);



                List<String> selected = select(db, table, "*", where_col, where_val);
                for (String _item: selected) {
                    JSONObject __item = new JSONObject(_item);
                    String _row_id = __item.getString("__uuid");


                    TableReplicaObject tro = new TableReplicaObject(db, table);
                    // get nodes where replicas are stored
                    JSONObject _nodes = tro.getRowReplica(_row_id);

                    String _node_primary_id = _nodes.getString("primary");
                    String _node_primary_ip = ADD.network.getIPFromUUID(_node_primary_id);
                    String _node_secondary_id = _nodes.getString("secondary");
                    String _node_secondary_ip = ADD.network.getIPFromUUID(_node_secondary_id);

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
                    boolean success_primary_insert = ADD.network.communicateQueryLogSingleNode(_node_primary_id, _node_primary_ip, _jsonUpdateReplicate);
                    boolean success_secondary_insert = ADD.network.communicateQueryLogSingleNode(_node_secondary_id, _node_secondary_ip, _jsonUpdateReplicate);


//                    TableStorageObject tso = new TableStorageObject(db, table);
//                    JSONObject json = tso.getJsonFromRowId(_row_id);
//                    if (json.getString(where_col).equals(where_val)) {
//                        json.remove(where_col);
//                        json.put(update_key, update_val);
//                    }

                }




//                if (updated) tso.saveToDisk();

//                return updated;
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
            Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            if (tableExists(db, table)) {

                TableStorageObject tso = new TableStorageObject(db, table);
                boolean deleted = tso.delete(where_col, where_val);

                if (deleted) tso.saveToDisk();

                return deleted;

//                if (!isReplicationAction)
//                    QueryLog.localAppend(new JSONObject().put("type", "delete").put("db", db).put("table", table).toString());

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
            byte data[] = dbo.returnDBObytes();
            Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
            Files.write(file, data);

            //create directory path if not exists
            ADD.storage.createDatabasePathIfNotExists(db);

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
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);

            ADD.storage.createTablePathIfNotExists(db, table);

            if (tableExists(db, table)) {
                return false;
            } else {
                dbo.createTable(table);

                byte[] data = dbo.returnDBObytes();
                Files.write(file, data);
                loadMetaDatabasesToMemory();

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
        Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        if (dbo.tables != null && dbo.tables.size()>0) {
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
        DatabaseObject dbo = new DatabaseObject().loadExisting(file);

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
        return true;
    }



    /***
     * Get a List of existing Tables
     * @param db
     * @return List
     */
    public List getTables(String db) {
        Path file = Paths.get(Globals.data_path + db + Globals.meta_extention);
        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        if (dbo.tables == null) return null;
        else if (dbo.tables.size()>0) return dbo.tables;
        else return new ArrayList();
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
            } catch (Exception e) {}
        }
    }

}
