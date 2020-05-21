package gl.ao.add;

import gl.ao.add.storage.Storage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ADDTest {

    @Test
    void main() {

        ADD testADD = null;
        testADD.main(new String[] {});

        assertEquals(testADD.storage.getClass(), Storage.class);
    }
}