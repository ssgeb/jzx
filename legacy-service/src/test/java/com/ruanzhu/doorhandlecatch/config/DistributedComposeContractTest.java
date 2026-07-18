package com.ruanzhu.doorhandlecatch.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedComposeContractTest {

    @Test
    void composePinsAlibabaMiddlewareAndRequiredInfrastructure() throws Exception {
        Path root = findProjectRoot();
        String compose = Files.readString(root.resolve("deploy/distributed/compose.yml"));
        String seataImage = Files.readString(root.resolve("deploy/distributed/seata/Dockerfile"));

        assertThat(compose).contains("nacos/nacos-server:v3.0.3");
        assertThat(compose).contains("doorhandle/seata-server:2.5.0-jdk21-mysql9.1.0");
        assertThat(compose).contains("sentinel-dashboard");
        assertThat(compose).contains("mysql:8.4");
        assertThat(compose).contains("apache/kafka:");
        assertThat(compose).contains("redis:7.4");
        assertThat(compose).doesNotContain("gateway");
        assertThat(compose).contains("LOADER_PATH: /seata-server/libs");
        assertThat(seataImage).contains("apache/seata-server:2.5.0.jdk21");
        assertThat(seataImage).contains("mysql-connector-j");
        assertThat(seataImage).contains("/seata-server/libs/jdbc");
    }

    private static Path findProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate the Git project root");
    }
}
