package com.example.donutflipscanner.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class FixtureLoader {
    private FixtureLoader() {
    }

    static String read(String name) {
        try (InputStream stream = FixtureLoader.class.getResourceAsStream("/fixtures/" + name)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing fixture: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read test fixture", exception);
        }
    }
}
