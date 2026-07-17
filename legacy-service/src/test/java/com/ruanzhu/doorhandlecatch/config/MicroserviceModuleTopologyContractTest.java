package com.ruanzhu.doorhandlecatch.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MicroserviceModuleTopologyContractTest {

    private final Path root = findProjectRoot();

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

    @Test
    void rootPomDeclaresTransitionalMicroserviceModules() throws Exception {
        String pom = Files.readString(root.resolve("pom.xml"));

        assertThat(pom).contains("<packaging>pom</packaging>");
        assertThat(pom).contains("<module>legacy-service</module>");
        assertThat(pom).contains("<module>platform-common</module>");
        assertThat(pom).contains("<module>platform-security</module>");
        assertThat(pom).contains("<module>event-contracts</module>");
        assertThat(pom).contains("<module>auth-service</module>");
        assertThat(pom).contains("<module>resource-service</module>");
        assertThat(pom).contains("<module>detection-service</module>");
        assertThat(pom).contains("<module>assistant-service</module>");
    }
}
