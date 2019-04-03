package gl.ao.add.network;

import gl.ao.add.Construct;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Topology implements Serializable {

    static final long serialVersionUID = 1L;

    public List allNodes() {
        List ret = new ArrayList();

        for (String an: Construct.network.availableNodes.keySet()) {
            ret.add(new Object());
        }

        return ret;
    }

}
