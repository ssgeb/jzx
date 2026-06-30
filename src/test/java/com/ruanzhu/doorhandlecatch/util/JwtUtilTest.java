package com.ruanzhu.doorhandlecatch.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    @Test
    void rejectsPlaceholderJwtSecretBeforeSigningToken() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "your-secret-key");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 3600L);

        assertThatThrownBy(() -> jwtUtil.generateToken("admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("secure");
    }
}
