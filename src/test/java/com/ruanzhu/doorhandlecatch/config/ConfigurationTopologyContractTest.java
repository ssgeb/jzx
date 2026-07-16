package com.ruanzhu.doorhandlecatch.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationTopologyContractTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();
    private static final Path MAIN_JAVA = PROJECT_ROOT.resolve("src/main/java/com/ruanzhu/doorhandlecatch");
    private static final Path MAIN_RESOURCES = PROJECT_ROOT.resolve("src/main/resources");

    @Test
    void usesApplicationYamlAsTheOnlyApplicationConfigurationFile() {
        assertThat(MAIN_RESOURCES.resolve("application.yml")).exists();
        assertThat(MAIN_RESOURCES.resolve("application.properties")).doesNotExist();
    }

    @Test
    void usesOnlyTheSpringBoot3MybatisPlusStarter() throws IOException {
        String pom = Files.readString(PROJECT_ROOT.resolve("pom.xml"), UTF_8);

        assertThat(pom).contains("<artifactId>mybatis-plus-spring-boot3-starter</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>mybatis-spring-boot-starter</artifactId>");
        assertThat(pom).doesNotContain("<artifactId>mybatis-plus-boot-starter</artifactId>");
    }

    @Test
    void doesNotCarryMybatisPlusStartupWorkarounds() throws IOException {
        String applicationYaml = Files.readString(MAIN_RESOURCES.resolve("application.yml"), UTF_8);
        String applicationClass = Files.readString(MAIN_JAVA.resolve("DoorHandleCatchApplication.java"), UTF_8);

        assertThat(applicationYaml).doesNotContain("allow-bean-definition-overriding");
        assertThat(applicationClass).doesNotContain(
                "MybatisPlusAutoConfiguration",
                "ddlApplicationRunner",
                "enable-sql-runner"
        );
    }

    @Test
    void removesRedundantConfigurationClasses() {
        List<String> redundantConfigurationClasses = List.of(
                "CustomMybatisConfig.java",
                "MybatisPlusRunnerConfig.java",
                "DatabaseConfig.java",
                "DatabaseInitConfig.java",
                "WebConfig.java"
        );

        assertThat(redundantConfigurationClasses)
                .map(fileName -> MAIN_JAVA.resolve("config").resolve(fileName))
                .allSatisfy(path -> assertThat(path).doesNotExist());
    }

    @Test
    void reliesOnConsoleLoggingWithoutFileLoggingConfiguration() throws IOException {
        String applicationYaml = Files.readString(MAIN_RESOURCES.resolve("application.yml"), UTF_8);

        assertThat(MAIN_RESOURCES.resolve("logback-spring.xml")).doesNotExist();
        assertThat(applicationYaml).doesNotContain("logging.file", "name: logs/");
    }
}
