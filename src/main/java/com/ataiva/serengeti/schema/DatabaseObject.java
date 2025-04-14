package ms.ao.serengeti.schema;

import ms.ao.serengeti.helpers.Globals;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DatabaseObject implements Serializable {

    static final long serialVersionUID = 1L;

    public String name = "";

    public List<String> tables = new ArrayList<>();
//    public DatabaseObjectData tables2 = new DatabaseObjectData();

    public DatabaseObject() {}

    /***
     * Create a new Object
     * @param name
     * @param tables
     */
    public void createNew(String name, List<String> tables) {
        this.name = name;
        this.tables = tables==null ? new ArrayList<>() : tables;
    }

    /***
     * Create a new Table
     * @param name
     */
    public void createTable(String name) {
        this.tables.add(name);
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
