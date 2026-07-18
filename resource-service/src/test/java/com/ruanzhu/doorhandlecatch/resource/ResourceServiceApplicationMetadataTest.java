package com.ruanzhu.doorhandlecatch.resource;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceServiceApplicationMetadataTest {

    @Test
    void applicationHasExpectedServiceName() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertThat(yaml).contains("name: resource-service");
        assertThat(yaml).contains("optional:nacos:${spring.application.name}.yaml");
        assertThat(yaml).contains("eager: ${SENTINEL_EAGER:true}");
        assertThat(yaml).contains("port: ${SENTINEL_TRANSPORT_PORT:8720}");
    }
}
