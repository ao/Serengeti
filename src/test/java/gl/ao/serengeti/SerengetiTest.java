package gl.ao.serengeti;

import gl.ao.serengeti.storage.Storage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SerengetiTest {

    @Test
    void main() {

        gl.ao.serengeti.Serengeti testSerengeti = null;
        testSerengeti.main(new String[] {});

        assertEquals(testSerengeti.storage.getClass(), Storage.class);
    }
}