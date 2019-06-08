package gl.ao.add.schema;

import gl.ao.add.helpers.Globals;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class TableStorageObject implements Serializable {

    private String databaseName = "";
    private String tableName = "";

    static final long serialVersionUID = 1L;

    public Map<String, String> rows = new HashMap<>();

    public TableStorageObject() {}
    public TableStorageObject(String database, String table) {
        this.databaseName = database;
        this.tableName = table;
        TableStorageObject self = loadExisting();
        this.rows = self.rows;
    }

    public JSONObject getJsonFromRowId(String row_id) {
        return new JSONObject( rows.get(row_id) );
    }

    public String insert(JSONObject json) {
        String uuid = UUID.randomUUID().toString();
        rows.put(uuid, json.toString());
        return uuid;
    }
    public String insert(String row_id, JSONObject json) {
        rows.put(row_id, json.toString());
        return row_id;
    }
    public boolean update(String row_id, JSONObject json) {
        rows.replace(row_id, json.toString());
        return true;
    }
    public boolean update(String update_key, String update_val, String where_col, String where_val) {
        List<String> results = select(where_col, where_val);
        for (int i=0; i<results.size(); i++) {
            JSONObject json = new JSONObject(results.get(i).toString());
            String uuid = json.getString("__uuid");
            json.remove("__uuid");
            json.remove(where_col);
            json.put(update_key, update_val);
            rows.replace(uuid, json.toString());
            return true;
        }

        return false;
    }
    public boolean delete(String row_id) {
        rows.remove(row_id);
        return true;
    }
    public boolean delete(String where_col, String where_val) {
        List<String> results = select(where_col, where_val);
        for (int i=0; i<results.size(); i++) {
            JSONObject json = new JSONObject(results.get(i).toString());
            String uuid = json.getString("__uuid");
            json.remove("__uuid");
            json.remove(where_col);
            rows.remove(uuid);
            return true;
        }

        return false;
    }
    public List select(String col, String val) {
        List<String> ret = new ArrayList<>();

        for (String key: rows.keySet()) {
            JSONObject json = new JSONObject(rows.get(key));
            json.put("__uuid", key);
            if (col.equals("") && val.equals("")) {
                ret.add(json.toString());
            } else if (json.has(col) && json.getString(col).equals(val)) {
                ret.add(json.toString());
                return ret;
            }
        }

        return ret;
    }

    public TableStorageObject loadExisting() {
        try {
            Path path = Paths.get(Globals.data_path + databaseName + "/" + tableName + "/" + Globals.storage_filename);
            TableStorageObject tableMeta = (TableStorageObject) Globals.convertFromBytes(Files.readAllBytes(path));
            System.out.println("Loaded storage table\t\t: '"+databaseName+"#"+tableMeta.tableName+"' ("+tableMeta.rows.size()+" rows)");
            return tableMeta;
        } catch (StreamCorruptedException sce) {
            System.out.println("Stream Corrupted Exception (TableStorageObject): "+sce.getMessage()+" - Could not 'loadExisting'");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new TableStorageObject();
    }

    public byte[] returnDBObytes() {
        return Globals.convertToBytes(this);
    }

    public boolean saveToDisk() {
        ObjectOutputStream oos = null;
        try {
            FileOutputStream fos = new FileOutputStream(Globals.data_path + databaseName + "/" + tableName + "/" + Globals.storage_filename);
            oos = new ObjectOutputStream(fos);
            oos.writeObject(this);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (oos != null) {
                try {
                    oos.close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        }
        return false;
    }
}
