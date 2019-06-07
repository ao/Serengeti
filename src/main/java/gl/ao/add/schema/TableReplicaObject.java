package gl.ao.add.schema;

import gl.ao.add.helpers.Globals;
import org.json.JSONObject;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class TableReplicaObject implements Serializable {

    String databaseName = "";
    String tableName = "";

    static final long serialVersionUID = 1L;

    public Map<String, String> row_replicas = new HashMap<>();

    public TableReplicaObject() {}
    public TableReplicaObject(String database, String table) {
        this.databaseName = database;
        this.tableName = table;
        TableReplicaObject self = loadExisting();
        this.row_replicas = self.row_replicas;
    }

    public boolean insert(String row_id, JSONObject json) {
        row_replicas.put(row_id, json.toString());
        return true;
    }
    public boolean delete(String row_id) {
        row_replicas.remove(row_id);
        return true;
    }

    public JSONObject getRowReplica(String row_id) {
        String row = row_replicas.get(row_id);
        if (!row.isEmpty()) {
            return new JSONObject(row);
        }
        return null;
    }

    public TableReplicaObject loadExisting() {
        try {
            Path path = Paths.get(Globals.data_path + databaseName + "/" + tableName + "/" + Globals.replica_filename);
            TableReplicaObject tableMeta = (TableReplicaObject) Globals.convertFromBytes(Files.readAllBytes(path));
            return tableMeta;
        } catch (StreamCorruptedException sce) {
            System.out.println("Stream Corrupted Exception (TableReplicaObject): "+sce.getMessage()+" - Could not 'loadExisting'");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new TableReplicaObject();
    }

    public byte[] returnDBObytes() {
        return Globals.convertToBytes(this);
    }

    public boolean saveToDisk() {
        try {
            FileOutputStream fos = new FileOutputStream(Globals.data_path + databaseName + "/" + tableName + "/" + Globals.replica_filename);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(this);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

}
