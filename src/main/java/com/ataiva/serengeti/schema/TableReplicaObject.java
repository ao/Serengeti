package com.ataiva.serengeti.schema;

import com.ataiva.serengeti.helpers.Globals;
import org.json.JSONObject;

import java.io.*;
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

    public boolean insertOrReplace(String row_id, JSONObject json) {
        row_replicas.put(row_id, json.toString());
        return true;
    }
    public boolean delete(String row_id) {
        row_replicas.remove(row_id);
        return true;
    }
    public JSONObject updateNewPrimary(String row_id, String primary_id) {
        String row = row_replicas.get(row_id);
        JSONObject json = new JSONObject(row);
        json.put("primary", primary_id);
        row_replicas.put(row_id, json.toString());
        return json;
    }
    public JSONObject updateNewSecondary(String row_id, String secondary_id) {
        String row = row_replicas.get(row_id);
        JSONObject json = new JSONObject(row);
        json.put("secondary", secondary_id);
        row_replicas.put(row_id, json.toString());
        return json;
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
            Object obj = Globals.convertFromBytes(Files.readAllBytes(path));
            
            if (obj == null) {
                System.out.println("Warning: Could not deserialize table replica object. Creating new one for " + databaseName + "#" + tableName);
                return new TableReplicaObject();
            }
            
            if (obj instanceof TableReplicaObject) {
                TableReplicaObject tableMeta = (TableReplicaObject) obj;
                System.out.println("Loaded replica table \t\t: '"+databaseName+"#"+tableMeta.tableName+"' ("+tableMeta.row_replicas.size()+" rows)");
                return tableMeta;
            } else {
                System.out.println("Warning: Deserialized object is not a TableReplicaObject. Creating new one for " + databaseName + "#" + tableName);
                return new TableReplicaObject();
            }
        } catch (ClassCastException e) {
            System.out.println("Warning: Class cast exception when deserializing table replica object: " + e.getMessage());
            return new TableReplicaObject();
        } catch (StreamCorruptedException sce) {
            System.out.println("Warning: Stream Corrupted Exception (TableReplicaObject): "+sce.getMessage()+" - Could not 'loadExisting'");
            return new TableReplicaObject();
        } catch (Exception e) {
            System.out.println("Warning: Error deserializing table replica object: " + e.getMessage());
            return new TableReplicaObject();
        }
    }

    public byte[] returnDBObytes() {
        return Globals.convertToBytes(this);
    }

    public boolean saveToDisk() {
        ObjectOutputStream oos = null;
        try {
            FileOutputStream fos = new FileOutputStream(Globals.data_path + databaseName + "/" + tableName + "/" + Globals.replica_filename);
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
