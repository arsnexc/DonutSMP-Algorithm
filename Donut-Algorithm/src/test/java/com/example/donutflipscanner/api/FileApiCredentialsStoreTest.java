package com.example.donutflipscanner.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FileApiCredentialsStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesCredentialsSeparatelyAndReturnsDefensiveCopies() {
        Path path = temporaryDirectory.resolve("api-key.txt");
        FileApiCredentialsStore store = new FileApiCredentialsStore(path);
        char[] supplied = "private-fixture-key".toCharArray();
        store.setApiKey(supplied);

        char[] first = store.copyApiKey().orElseThrow();
        first[0] = 'X';
        char[] second = store.copyApiKey().orElseThrow();
        assertArrayEquals("private-fixture-key".toCharArray(), second);

        Arrays.fill(first, '\0');
        Arrays.fill(second, '\0');
        store.clear();
        assertFalse(Files.exists(path));
    }

    @Test
    void trimsEditorAddedOuterWhitespaceButNeverReturnsIt() throws Exception {
        Path path = temporaryDirectory.resolve("api-key.txt");
        Files.writeString(path, "\r\n  private-fixture-key  \r\n");

        char[] copied = new FileApiCredentialsStore(path).copyApiKey().orElseThrow();

        assertArrayEquals("private-fixture-key".toCharArray(), copied);
        Arrays.fill(copied, '\0');
    }
}
