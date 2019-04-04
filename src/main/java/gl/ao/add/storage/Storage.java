package gl.ao.add.storage;

import gl.ao.add.Construct;
import gl.ao.add.query.QueryLog;
import gl.ao.add.schema.DatabaseObject;
import gl.ao.add.helpers.Globals;
import gl.ao.add.schema.TableObject;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.ObjectOutputStream;
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
            Path file = Paths.get(Construct.data_path + databaseName + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            databases.put(databaseName, dbo);
        }
    }

    /***
     * Get a List of existing Databases
     * @return List
     */
    public List getDatabases() {
        File dir = new File(Construct.data_path);
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
     * Get a Piece ID
     * @param db
     * @param table
     * @return String
     */
    public String getPieceId(String db, String table) {
        Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        if (tableExists(db, table)) {
            Map<String, Integer> t = dbo.tables.get(table);
            for (String key : t.keySet()) {
                if (t.get(key) == 0) return key;
            }
        }
        return null;
    }

    /***
     * Select
     * @param db
     * @param table
     * @param selectWhat
     * @param col
     * @param val
     * @return List<String>
     */
    public List<String> select(String db, String table, String selectWhat, String col, String val) {
        try {
            Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            if (tableExists(db, table)) {
                List<String> list = new ArrayList<>();
                Map<String, Integer> t = dbo.tables.get(table);

                for (String key : t.keySet()) {
                    TableObject to = new TableObject(db, table, key);
                    JSONObject got = to.get(col, val);
                    if (got!=null) {
                        if (selectWhat.equals("*")) {
                            list.add(got.toString());
                        } else {
                            list.add(got.get(selectWhat).toString());
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

            String pieceId = getPieceId(db, table);
            if (new TableObject().isPieceFull(db, table, pieceId)) {
                Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
                DatabaseObject dbo = new DatabaseObject().loadExisting(file);
                String oldPiece = pieceId;
                pieceId = createBlankShardPiece(db, table);
                dbo.tables = dbo.updateTableNewPieceId(db, table, oldPiece, pieceId);
                try {
                    byte[] data = dbo.returnDBObytes();
                    Files.write(file, data);
                    loadMetaDatabasesToMemory();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            TableObject to = new TableObject(db, table, pieceId);
            String rowId = to.add(json);
            to.saveToDisk();

            if (!isReplicationAction)
                QueryLog.localAppend(new JSONObject().put("type", "insert").put("db", db).put("table", table).put("json", json).put("rowId", rowId).put("pieceId", pieceId).toString());

            sro.pieceId = pieceId;
            sro.rowId = rowId;
            sro.success = true;

            for (Object key : json.keySet()) {
                String k = (String) key;
                String v = (String) json.get(k);
                sro.index.put(k, v);
            }

            return sro;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return sro;
    }

    /***
     * Update row item with a replica uuid
     * @param db
     * @param table
     * @param pieceId
     * @param rowId
     * @param replica
     */
    public void updateReplicaByRowId(String db, String table, String pieceId, String rowId, String replica) {
        TableObject to = new TableObject(db, table, pieceId);
        to.loadExisting();
        to.updateReplicaToRow(rowId, replica);
        to.saveToDisk();
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
            Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            if (tableExists(db, table)) {
                Map<String, Integer> pieces = dbo.tables.get(table);

                for (String pieceId : pieces.keySet()) {
                    TableObject to = new TableObject(db, table, pieceId);
                    to.update(update_key, update_val, where_col, where_val);
                    to.saveToDisk();
                }

                if (!isReplicationAction)
                    QueryLog.localAppend(new JSONObject().put("type", "update").put("update_key", update_key).put("update_val", update_val).put("where_col", where_col).put("where_val", where_val).put("db", db).put("table", table).toString());

                return true;
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
            Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);
            if (tableExists(db, table)) {
                Map<String, Integer> pieces = dbo.tables.get(table);

                for (String pieceId : pieces.keySet()) {
                    TableObject to = new TableObject(db, table, pieceId);
                    to.delete(where_col, where_val);
                    to.saveToDisk();
                }

                if (!isReplicationAction)
                    QueryLog.localAppend(new JSONObject().put("type", "delete").put("db", db).put("table", table).toString());

                return true;
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
        File f = new File(Construct.data_path + db + Globals.meta_extention);
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
            dbo.createNew(db, Construct.me, null);
            byte data[] = dbo.returnDBObytes();
            Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
            Files.write(file, data);
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
            Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
            boolean deleted = Files.deleteIfExists(file);
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
            Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
            DatabaseObject dbo = new DatabaseObject().loadExisting(file);

            Construct.storage.createTablePathIfNotExists(db, table);
            String newPieceId = Construct.storage.createBlankShardPiece(db, table);

            if (tableExists(db, table)) {
                return false;
            } else {
                dbo.createTable(table, newPieceId);

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
        Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        if (dbo.tables != null && dbo.tables.size()>0) {
            return dbo.tables.containsKey(table);
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
        Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        if (dbo.tables.size()>0) {
            for (String t: dbo.tables.keySet()) {
                if (t.equals(table)) {
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
        Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        if (dbo.tables == null) {
            return null;
        } else if (dbo.tables.size()>0) {
            List<String> _tables = new ArrayList<>();
            for (String t: dbo.tables.keySet()) {
                _tables.add(t);
            }
            return _tables;
        } else return null;
    }

    /***
     * Create Table Path if not exists
     * @param db
     * @param table
     * @return boolean
     */
    public boolean createTablePathIfNotExists(String db, String table) {
        try {
            // make sure `pieces` directory exists
            File _pieces_dir = new File(Globals.pieces_path);
            if (!_pieces_dir.exists()) _pieces_dir.mkdir();
            // make sure `database` directory exists
            File _db_dir = new File(Globals.pieces_path + db + "/");
            if (!_db_dir.exists()) _db_dir.mkdir();
            // make sure `table` directory exists
            File _table_dir = new File(Globals.pieces_path + db + "/" + table + "/");
            if (!_table_dir.exists()) {
                _table_dir.mkdir();
                return true;
            }
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
            File _table_dir = new File(Globals.pieces_path + db + "/" + table + "/");
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
     * Create Blank Shard Piece
     * @param db
     * @param table
     * @return String
     */
    public String createBlankShardPiece(String db, String table) {
        return createBlankShardPiece(db, table, false);
    }
    public String createBlankShardPiece(String db, String table, boolean isReplicationAction) {
        File _table_dir = new File(Globals.pieces_path + db + "/" + table + "/");
        if (_table_dir.exists()) {

            String newPieceId = UUID.randomUUID().toString();
            try {
                FileOutputStream fos = new FileOutputStream(Globals.pieces_path + db + "/" + table + "/" + newPieceId + Globals.piece_extention);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                oos.writeObject(new TableObject());

                if (!isReplicationAction)
                    QueryLog.localAppend(new JSONObject().put("type", "createBlankShardPiece").put("table", table).put("db", db).toString());

                return newPieceId;
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            createTablePathIfNotExists(db, table);
            return createBlankShardPiece(db, table);
        }
        return null;
    }

}
