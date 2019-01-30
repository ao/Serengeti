package gl.ao.add.query;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class QueryResponseObject {
    String query = null;
    String error = null;
    Boolean executed = false;
    String explain = null;
    String runtime = null;
    List<String> list = null;

    public QueryResponseObject() {}

    /***
     * Return JSON
     * @return
     */
    public JSONObject json() {
        JSONObject json = new JSONObject();

        json.put("query", this.query);
        json.put("error", this.error);
        json.put("executed", this.executed);
        json.put("explain", this.explain);
        json.put("runtime", this.runtime);
        json.put("list", new JSONArray(this.list));

        return json;
    }
}
