package gl.ao.serengeti.ui;

import gl.ao.serengeti.helpers.Globals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Dashboard {

    /***
     * Render the Dashboard page
     * @param host
     * @param uri
     * @return
     * @throws IOException
     */
    public static String IndexTemplate(String host, String uri) throws IOException {
        return new String(
            Files.readAllBytes(
                Paths.get(Globals.res_path+"dashboard.html")
            ),
            StandardCharsets.UTF_8
        );
    }
}

