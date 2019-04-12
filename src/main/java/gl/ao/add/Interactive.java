package gl.ao.add;

import gl.ao.add.helpers.Globals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Interactive {

    /***
     * Render the Interactive page
     * @param host
     * @param uri
     * @return
     */
    public static String IndexTemplate(String host, String uri) throws IOException {
        String htmlString = new String(Files.readAllBytes(Paths.get(Globals.res_path + "interactive.html")), "UTF-8");
        return htmlString;
    }
}
