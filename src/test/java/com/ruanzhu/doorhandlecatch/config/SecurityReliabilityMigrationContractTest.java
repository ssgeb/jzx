package com.ruanzhu.doorhandlecatch.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityReliabilityMigrationContractTest {

    private static final Path MIGRATION = Path.of("src", "main", "resources", "db",
            "migration-V14-security-reliability-hardening.sql");

    @Test
    void migrationAddsEveryColumnWithInformationSchemaGuards() throws IOException {
        String sql = migrationSql();

        assertThat(sql).contains("information_schema.columns", "table_schema = database()",
                "column_name = p_column");
        assertThat(sql).contains("v14_add_column_if_missing('users', 'role'",
                "v14_add_column_if_missing('detection_task', 'dispatch_id'",
                "v14_add_column_if_missing('detection_task', 'last_finished_event_id'",
                "v14_add_column_if_missing('chat_pending_action', 'error_message'");
        assertThat(sql).contains("`role` varchar(16) not null default ''operator''",
                "`dispatch_id` varchar(64) null", "`last_finished_event_id` varchar(128) null",
                "`error_message` varchar(500) null");
    }

    @Test
    void migrationBackfillsAdminAndFallsBackUnsupportedRolesToOperator() throws IOException {
        String sql = migrationSql();

        assertThat(sql).contains("set `role` = 'admin'", "trim(`username`)", "= 'admin'");
        assertThat(sql).contains("set `role` = 'operator'", "trim(`role`) = ''",
                "upper(trim(`role`)) not in ('admin', 'operator')");
    }

    @Test
    void migrationAddsGuardedDispatchIndex() throws IOException {
        String sql = migrationSql();

        assertThat(sql).contains("information_schema.statistics", "index_name = p_index",
                "concat('create index `', p_index, '` on `', p_table, '` ', p_definition)",
                "v14_add_index_if_missing('detection_task', 'idx_detection_task_dispatch_id', '(`dispatch_id`)')");
    }

    @Test
    void schemaMirrorsColumnsIndexAndAdminSeedRole() throws IOException {
        String schema = normalized(Path.of("src", "main", "resources", "db", "schema.sql"));

        assertThat(schema).contains("`role` varchar(16) not null default 'operator'",
                "`dispatch_id` varchar(64) default null", "`last_finished_event_id` varchar(128) default null",
                "`error_message` varchar(500) default null",
                "key `idx_detection_task_dispatch_id` (`dispatch_id`)",
                "insert ignore into users (id, username, password, email, phone, role)",
                "'admin')");
    }

    @Test
    void applicationRegistersV14ImmediatelyAfterV13() throws IOException {
        String application = normalized(Path.of("src", "main", "resources", "application.yml"));
        int databaseSection = application.indexOf("database:");
        int scripts = application.indexOf("migration-scripts:", databaseSection);
        int v13 = application.indexOf("classpath:db/migration-v13-business-seed-data-normalization.sql", scripts);
        int v14 = application.indexOf("classpath:db/migration-v14-security-reliability-hardening.sql", scripts);

        assertThat(databaseSection).isGreaterThanOrEqualTo(0);
        assertThat(scripts).isGreaterThan(databaseSection);
        assertThat(v13).isGreaterThan(scripts);
        assertThat(v14).isGreaterThan(v13);
    }

    private String migrationSql() throws IOException {
        assertThat(MIGRATION).as("V14 migration must exist").exists();
        return normalized(MIGRATION);
    }

    private String normalized(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
