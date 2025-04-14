package com.ataiva.serengeti.ui;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class DashboardTest {

    @Test
    void indexTemplate() throws IOException, URISyntaxException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("html/dashboard.html");
        String dashboard = new BufferedReader(
                new InputStreamReader(Objects.requireNonNull(inputStream), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        assertEquals(new Dashboard().IndexTemplate("test", "test"), dashboard);
    }
}
