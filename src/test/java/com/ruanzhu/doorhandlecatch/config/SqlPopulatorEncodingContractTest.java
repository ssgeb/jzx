package com.ruanzhu.doorhandlecatch.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlPopulatorEncodingContractTest {

    private static final String UTF8_CONFIGURATION =
            "populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());";

    @Test
    void customDatabasePopulatorsExplicitlyReadSqlAsUtf8() throws IOException {
        assertUtf8Populator("BusinessSeedDataConfig.java");
        assertUtf8Populator("DatabaseInitConfig.java");
    }

    private void assertUtf8Populator(String fileName) throws IOException {
        Path source = Path.of(
                "src", "main", "java", "com", "ruanzhu", "doorhandlecatch", "config", fileName);
        String content = Files.readString(source, StandardCharsets.UTF_8);

        assertTrue(content.contains("import java.nio.charset.StandardCharsets;"),
                fileName + " must import StandardCharsets");
        assertTrue(content.contains(UTF8_CONFIGURATION),
                fileName + " must force UTF-8 SQL script decoding");
    }
}
