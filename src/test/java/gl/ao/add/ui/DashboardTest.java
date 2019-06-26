package gl.ao.add.ui;

import gl.ao.add.helpers.Globals;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class DashboardTest {

    @Test
    void indexTemplate() throws IOException {
        String interactive = new String(Files.readAllBytes(Paths.get(Globals.res_path + "dashboard.html")));
        assertEquals(Dashboard.IndexTemplate("test", "test"), interactive);
    }
}

