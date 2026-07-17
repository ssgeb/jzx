package com.ruanzhu.doorhandlecatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfigDefaultsTest {

    @Test
    void applicationNameRemainsDoorHandleCatchInYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = Objects.requireNonNull(yaml.getObject());

        assertThat(properties.getProperty("spring.application.name")).isEqualTo("DoorHandleCatch");
    }

    @Test
    void applicationConfigDoesNotShipWithRootDatabasePasswordOrJwtSecret() throws Exception {
        String config = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(config).doesNotContain("DB_PASSWORD:root");
        assertThat(config).doesNotContain("JWT_SECRET:doorhandlecatch");
    }

    @Test
    void businessSeedImportIsExplicitlyOptIn() throws Exception {
        String config = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(config).contains("APP_BUSINESS_SEED_ENABLED:false");
        assertThat(config).contains("business-seed-new-features.sql");
        assertThat(config).contains("business-seed-more-features.sql");
        assertThat(config).contains("business-seed-trace-rich.sql");
    }
}
