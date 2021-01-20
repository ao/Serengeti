package gl.ao.serengeti.ui;

import gl.ao.serengeti.helpers.Globals;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.stream.Collectors;

public class Dashboard {

    /***
     * Render the Dashboard page
     * @param host
     * @param uri
     * @return
     * @throws IOException
     */
    public String IndexTemplate(String host, String uri) throws IOException, URISyntaxException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("html/dashboard.html");
        return new BufferedReader(
                new InputStreamReader(Objects.requireNonNull(inputStream), StandardCharsets.UTF_8))
            .lines()
            .collect(Collectors.joining("\n"));
    }
}

