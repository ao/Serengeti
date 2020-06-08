package gl.ao.serengeti.storage;

import java.util.HashMap;
import java.util.Map;

public class StorageResponseObject {

    public Boolean success = false;
    public String pieceId = null;
    public String rowId = null;
    public String primary = null;
    public String secondary = null;
    Map<String, String> index = new HashMap<>();

}
