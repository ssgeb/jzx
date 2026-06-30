package com.ruanzhu.doorhandlecatch.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionConfigDefaultsTest {

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
