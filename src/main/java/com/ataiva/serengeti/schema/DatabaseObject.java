package com.ataiva.serengeti.schema;

import com.ataiva.serengeti.helpers.Globals;

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
            if (dbmeta == null) {
                System.out.println("Warning: Could not deserialize database object at " + path + ". Creating new one.");
                return new DatabaseObject();
            }
            if (dbmeta instanceof DatabaseObject) {
                return (DatabaseObject) dbmeta;
            } else {
                System.out.println("Warning: Deserialized object is not a DatabaseObject. Creating new one.");
                return new DatabaseObject();
            }
        } catch (ClassCastException e) {
            System.out.println("Warning: Class cast exception when deserializing database object: " + e.getMessage());
            return new DatabaseObject();
        } catch (Exception e) {
            System.out.println("Warning: Error deserializing database object: " + e.getMessage());
            return new DatabaseObject();
        }
    }

    /***
     * Return DBO bytes
     * @return
     */
    public byte[] returnDBObytes() {
        return Globals.convertToBytes(this);
    }

}
