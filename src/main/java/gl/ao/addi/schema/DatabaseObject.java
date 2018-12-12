package gl.ao.addi.schema;

import gl.ao.addi.Construct;
import gl.ao.addi.Globals;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class DatabaseObject implements Serializable {

    static final long serialVersionUID = 1L;

    public String name = "";
    public String creator = null;

    public Map<String, Map<String, Integer>> tables = new HashMap<>();

    public DatabaseObject() {}

    /***
     * Create a new Object
     * @param name
     * @param creator
     * @param tables
     */
    public void createNew(String name, String creator, Map<String, Map<String, Integer>> tables) {
        this.name = name;
        this.creator = creator;
        this.tables = tables==null ? new HashMap<>() : tables;
    }

    /***
     * Create a new Table
     * @param name
     */
    public void createTable(String name, String newPieceId) {
        Map<String, Integer> piece = new HashMap<>();
        piece.put(newPieceId, 0);
        if (this.tables == null) {
            this.tables = new HashMap<>();
        }
        this.tables.put(name, piece);
    }

    public Map updateTableNewPieceId(String db, String table, String oldPieceId, String newPieceId) {
        Path file = Paths.get(Construct.data_path + db + Globals.meta_extention);
        DatabaseObject dbo = new DatabaseObject().loadExisting(file);

        if (dbo.tables == null) {
            return null;
        }
        dbo.tables.get(table).put(newPieceId, 0);

        dbo.tables.get(table).replace(oldPieceId, 0, 1);

        byte[] data = dbo.returnDBObytes();
        try {
            Files.write(file, data);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return dbo.tables;
    }

    /***
     * Load an Existing Object
     * @param path
     * @return
     */
    public DatabaseObject loadExisting(Path path) {
        try {
            Object dbmeta = Globals.convertFromBytes(Files.readAllBytes(path));
            return (DatabaseObject) dbmeta;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new DatabaseObject();
    }

    /***
     * Return DBO bytes
     * @return
     */
    public byte[] returnDBObytes() {
        return Globals.convertToBytes(this);
    }

}
