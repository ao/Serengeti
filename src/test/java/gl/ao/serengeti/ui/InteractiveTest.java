package gl.ao.serengeti.ui;

import gl.ao.serengeti.helpers.Globals;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveTest {

    @Test
    void indexTemplate() throws IOException {
        String interactive = new String(Files.readAllBytes(Paths.get(Globals.res_path + "interactive.html")));
        assertEquals(Interactive.IndexTemplate("test", "test"), interactive);
    }
}