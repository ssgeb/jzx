package com.ruanzhu.doorhandlecatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ConfigurationTopologyContractTest {

    private static final Set<String> BASE_APPLICATION_CONFIG_NAMES = Set.of(
            "application.yml",
            "application.yaml",
            "application.properties"
    );
    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path MAIN_JAVA = PROJECT_ROOT.resolve("src/main/java");
    private static final Path APPLICATION_PACKAGE = MAIN_JAVA.resolve("com/ruanzhu/doorhandlecatch");
    private static final Path MAIN_RESOURCES = PROJECT_ROOT.resolve("src/main/resources");
    private static final Path APPLICATION_YAML = MAIN_RESOURCES.resolve("application.yml");

    @Test
    void usesApplicationYamlAsTheOnlyBaseApplicationConfigurationFile() throws IOException {
        List<String> baseApplicationConfigs;
        try (Stream<Path> resources = Files.list(MAIN_RESOURCES)) {
            baseApplicationConfigs = resources
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(BASE_APPLICATION_CONFIG_NAMES::contains)
                    .sorted()
                    .toList();
        }

        assertThat(baseApplicationConfigs).containsExactly("application.yml");
    }

    @Test
    void usesOnlyTheSpringBoot3MybatisPlusStarter() throws Exception {
        Set<String> dependencies = projectDependencies(PROJECT_ROOT.resolve("pom.xml"));

        assertSoftly(softly -> {
            softly.assertThat(dependencies).contains("com.baomidou:mybatis-plus-spring-boot3-starter");
            softly.assertThat(dependencies).doesNotContain(
                    "org.mybatis.spring.boot:mybatis-spring-boot-starter",
                    "com.baomidou:mybatis-plus-boot-starter"
            );
        });
    }

    @Test
    void doesNotCarryMybatisPlusStartupWorkarounds() throws IOException {
        Properties applicationProperties = loadApplicationProperties();

        List<String> forbiddenPropertiesPresent = forbiddenPropertiesPresent(
                applicationProperties,
                "spring.main.allow-bean-definition-overriding",
                "mybatis-plus.global-config.enable-sql-runner"
        );

        List<String> workaroundTokens = List.of(
                "MybatisPlusAutoConfiguration",
                "ddlApplicationRunner",
                "enable-sql-runner"
        );
        Map<Path, List<String>> workaroundsBySource = new LinkedHashMap<>();
        List<Path> javaSources;
        try (Stream<Path> sourceFiles = Files.walk(MAIN_JAVA)) {
            javaSources = sourceFiles
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }
        for (Path javaSource : javaSources) {
            String source = Files.readString(javaSource, UTF_8);
            List<String> matchingTokens = workaroundTokens.stream()
                    .filter(source::contains)
                    .toList();
            if (!matchingTokens.isEmpty()) {
                workaroundsBySource.put(PROJECT_ROOT.relativize(javaSource), matchingTokens);
            }
        }

        assertSoftly(softly -> {
            softly.assertThat(forbiddenPropertiesPresent)
                    .as("forbidden expanded application.yml property names")
                    .isEmpty();
            softly.assertThat(workaroundsBySource)
                    .as("MyBatis-Plus startup workaround tokens by Java source")
                    .isEmpty();
        });
    }

    @Test
    void removesRedundantConfigurationClasses() {
        Path configPackage = APPLICATION_PACKAGE.resolve("config");
        List<String> redundantConfigurationClasses = List.of(
                "CustomMybatisConfig.java",
                "MybatisPlusRunnerConfig.java",
                "DatabaseConfig.java",
                "DatabaseInitConfig.java",
                "WebConfig.java"
        );

        assertThat(redundantConfigurationClasses)
                .map(configPackage::resolve)
                .allSatisfy(path -> assertThat(path).doesNotExist());
    }

    @Test
    void reliesOnConsoleLoggingWithoutFileLoggingConfiguration() {
        Properties applicationProperties = loadApplicationProperties();

        List<String> forbiddenPropertiesPresent = forbiddenPropertiesPresent(
                applicationProperties,
                "logging.file.name",
                "logging.file.path",
                "logging.config"
        );
        assertSoftly(softly -> {
            softly.assertThat(MAIN_RESOURCES.resolve("logback-spring.xml")).doesNotExist();
            softly.assertThat(MAIN_RESOURCES.resolve("logback.xml")).doesNotExist();
            softly.assertThat(forbiddenPropertiesPresent)
                    .as("forbidden expanded application.yml property names")
                    .isEmpty();
        });
    }

    private static Properties loadApplicationProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(APPLICATION_YAML));
        return Objects.requireNonNull(
                yaml.getObject(),
                () -> "Unable to load YAML properties from " + APPLICATION_YAML
        );
    }

    private static Set<String> propertyNames(Properties properties) {
        return properties.keySet().stream()
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static List<String> forbiddenPropertiesPresent(Properties properties, String... forbiddenNames) {
        Set<String> propertyNames = propertyNames(properties);
        return Stream.of(forbiddenNames)
                .filter(propertyNames::contains)
                .toList();
    }

    private static Set<String> projectDependencies(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);

        Document document;
        try (InputStream input = Files.newInputStream(pom)) {
            document = factory.newDocumentBuilder().parse(input);
        }

        Element dependenciesElement = directChild(document.getDocumentElement(), "dependencies");
        Set<String> dependencies = new LinkedHashSet<>();
        NodeList children = dependenciesElement.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element dependency && hasName(dependency, "dependency")) {
                dependencies.add(
                        directChild(dependency, "groupId").getTextContent().trim()
                                + ":"
                                + directChild(dependency, "artifactId").getTextContent().trim()
                );
            }
        }
        return dependencies;
    }

    private static Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element element && hasName(element, name)) {
                return element;
            }
        }
        throw new IllegalStateException("Missing <" + name + "> under <" + parent.getTagName() + ">");
    }

    private static boolean hasName(Element element, String name) {
        String localName = element.getLocalName();
        return name.equals(localName != null ? localName : element.getTagName());
    }

    private static Path locateProjectRoot() {
        String basedir = System.getProperty("basedir");
        if (basedir != null && !basedir.isBlank()) {
            Path root = findProjectRoot(Path.of(basedir));
            if (root != null) {
                return root;
            }
        }

        Path workingDirectory = Path.of("").toAbsolutePath();
        Path root = findProjectRoot(workingDirectory);
        if (root != null) {
            return root;
        }
        throw new IllegalStateException(
                "Unable to locate project root containing pom.xml from basedir='"
                        + basedir
                        + "' or working directory '"
                        + workingDirectory
                        + "'"
        );
    }

    private static Path findProjectRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }
}
