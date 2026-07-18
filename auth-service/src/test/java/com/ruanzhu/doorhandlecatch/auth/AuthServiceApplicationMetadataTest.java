package com.ruanzhu.doorhandlecatch.auth;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AuthServiceApplicationMetadataTest {

    @Test
    void applicationHasExpectedServiceName() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("name: auth-service");
        assertThat(yaml).contains("optional:nacos:${spring.application.name}.yaml");
    }
}
