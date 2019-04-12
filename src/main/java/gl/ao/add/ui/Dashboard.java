package gl.ao.add.ui;

import gl.ao.add.helpers.Globals;

import java.io.IOException;
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
        String htmlString = new String(Files.readAllBytes(Paths.get(Globals.res_path+"dashboard.html")), "UTF-8");
        return htmlString;
    }
}
