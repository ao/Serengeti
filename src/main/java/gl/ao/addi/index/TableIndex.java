package gl.ao.addi.index;

import gl.ao.addi.helpers.Globals;

import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TableIndex {

    static final long serialVersionUID = 1L;

    public String databaseName = "";
    public String tableName = "";
    public String indexId = "";


    public TableIndex loadExisting() {
        try {
            Path path = Paths.get(Globals.pieces_path + databaseName + "/" + tableName + "/" + indexId + Globals.index_extension);
            TableIndex tableMeta = (TableIndex) Globals.convertFromBytes(Files.readAllBytes(path));
            return tableMeta;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new TableIndex();
    }

    public boolean saveToDisk() {
        try {
            FileOutputStream fos = new FileOutputStream(Globals.pieces_path + databaseName + "/" + tableName + "/" + indexId + Globals.index_extension);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(this);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
