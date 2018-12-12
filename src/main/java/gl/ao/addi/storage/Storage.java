package gl.ao.addi.storage;

import gl.ao.addi.Construct;
import gl.ao.addi.schema.DatabaseObject;
import gl.ao.addi.Globals;
import gl.ao.addi.schema.TableObject;
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
     * @return
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
     * @return
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
     * @return
     */
    public StorageResponseObject insert(String db, String table, JSONObject json) {
        StorageResponseObject sro = new StorageResponseObject();

        try {
            if (!tableExists(db, table)) createTable(db, table);

            createTablePathIfNotExists(db, table);

            String pieceId = getPieceId(db, table);
            TableObject to = new TableObject();
            if (to.isPieceFull(db, table, pieceId)) {
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

            TableObject _table = new TableObject(db, table, pieceId);
            _table.add(json);
            _table.saveToDisk();

            sro.pieceId = pieceId;
            sro.success = true;

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
     * @param key
     * @param val
     * @param where_col
     * @param where_val
     * @return
     */
    public boolean update(String db, String table, String key, String val, String where_col, String where_val) {
        try {


            return true;
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
     * @return
     */
    public boolean delete(String db, String table, String where_col, String where_val) {
        try {



            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }


    /***
     * Does Database Exist?
     * @param db
     * @return
     */
    public boolean databaseExists(String db) {
        File f = new File(Construct.data_path + db + Globals.meta_extention);
        return f.exists();
    }

    /***
     * Create a new Database
     * @param db
     * @return
     */
    public boolean createDatabase(String db) {
        try {
            DatabaseObject dbo = new DatabaseObject();
            dbo.createNew(db, Construct.me, null);
            byte data[] = dbo.returnDBObytes();
            Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
            Files.write(file, data);
            loadMetaDatabasesToMemory();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /***
     * Drop an existing Database
     * @param db
     * @return
     */
    public boolean dropDatabase(String db) {
        try {
            Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
            boolean deleted = Files.deleteIfExists(file);
            loadMetaDatabasesToMemory();
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
     * @return
     */
    public boolean createTable(String db, String table) {
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
     * @return
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
     * @return
     */
    public boolean dropTable(String db, String table) {
        Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
        DatabaseObject dbo = new DatabaseObject().loadExisting(file);
        if (dbo.tables.size()>0) {
            for (String t: dbo.tables.keySet()) {
                if (t.equals(table)) {
                    dbo.tables.remove(t);
                    byte[] data = dbo.returnDBObytes();
                    try {
                        Files.write(file, data);
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
     * @return
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

    public String createBlankShardPiece(String db, String table) {
        File _table_dir = new File(Globals.pieces_path + db + "/" + table + "/");
        if (_table_dir.exists()) {

            String newPieceId = UUID.randomUUID().toString();
            try {
                FileOutputStream fos = new FileOutputStream(Globals.pieces_path + db + "/" + table + "/" + newPieceId + Globals.piece_extention);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                oos.writeObject(new TableObject());
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
