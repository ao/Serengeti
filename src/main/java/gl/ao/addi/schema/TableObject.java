package gl.ao.addi.schema;

import gl.ao.addi.helpers.Globals;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class TableObject implements Serializable {

    static final long serialVersionUID = 1L;

    public String databaseName = "";
    public String tableName = "";
    public String pieceId = "";

    public Map<String, String> rows = new HashMap<>();

    public TableObject() {}
    public TableObject(String database, String table, String pieceId) {
        this.databaseName = database;
        this.tableName = table;
        this.pieceId = pieceId;
        TableObject self = loadExisting();
        this.rows = self.rows;
    }

    /***
     * Load an Existing Object
     * @return
     */
    public TableObject loadExisting() {
        try {
            Path path = Paths.get(Globals.pieces_path + databaseName + "/" + tableName + "/" + pieceId + Globals.piece_extention);
            TableObject tableMeta = (TableObject) Globals.convertFromBytes(Files.readAllBytes(path));
            return tableMeta;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new TableObject();
    }
    public boolean isPieceFull(String db, String table, String pieceId) {
        try {
            Path path = Paths.get(Globals.pieces_path + db + "/" + table + "/" + pieceId + Globals.piece_extention);
            Long size = path.toFile().length();
            System.out.println(size.toString());
            if (size >= Globals.piece_size) return true;
            else return false;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    /***
     * Return DBO bytes
     * @return
     */
    public byte[] returnDBObytes() {
        return Globals.convertToBytes(this);
    }

    /***
     * Add row to rows map
     * @param json
     * @return
     */
    public boolean add(JSONObject json) {
        String rowId = UUID.randomUUID().toString();
        rows.put(rowId, json.toString());
        return true;
    }
    public JSONObject get(String key, String value) {
        for (String k: rows.keySet()) {
            String row = rows.get(k);
            JSONObject json = new JSONObject(row);
            if (json.has(key) && json.get(key).equals(value)) {
                JSONObject returnJSON = json;
                returnJSON.put("__key", k);
                returnJSON.put("__piece", pieceId);
                return returnJSON;
            }
        }
        return null;
    }
    public boolean update(String update_key, String update_val, String where_col, String where_val) {
        for (String k: rows.keySet()) {
            String row = rows.get(k);
            JSONObject json = new JSONObject(row);
            if (json.has(where_col) && json.get(where_col).equals(where_val)) {
                JSONObject r = new JSONObject(rows.get(k));
                r.put(update_key, update_val);
                rows.put(k, r.toString());
            }
        }
        return true;
    }
    public boolean delete(String where_col, String where_val) {
        List<String> keysToDelete = new LinkedList<>();
        for (String k: rows.keySet()) {
            String row = rows.get(k);
            JSONObject json = new JSONObject(row);
            if (json.has(where_col) && json.get(where_col).equals(where_val)) {
                //rows.remove(k); -- don't do this, otherwise you'll get an java.util.ConcurrentModificationException !
                keysToDelete.add(k);
            }
        }
        for (int i=0; i<keysToDelete.size(); i++) {
            rows.remove(keysToDelete.get(i));
        }
        return true;
    }

    public boolean saveToDisk() {
        try {
            FileOutputStream fos = new FileOutputStream(Globals.pieces_path + databaseName + "/" + tableName + "/" + pieceId + Globals.piece_extention);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(this);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
