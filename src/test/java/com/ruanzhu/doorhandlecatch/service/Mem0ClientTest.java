package com.ruanzhu.doorhandlecatch.service;

import com.ruanzhu.doorhandlecatch.security.SensitiveDataSanitizer;
import com.ruanzhu.doorhandlecatch.security.TenantContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Mem0ClientTest {
    @Test
    void buildsNamespacedUserApplicationAndRunScope() {
        Mem0Client client = new Mem0Client(new SensitiveDataSanitizer());

        assertThat(client.buildScope(new TenantContext(42L, "alice"), "sess_abc"))
                .containsEntry("user_id", "doorhandlecatch:user:42")
                .containsEntry("app_id", "doorhandlecatch")
                .containsEntry("run_id", "sess_abc");
    }
}
