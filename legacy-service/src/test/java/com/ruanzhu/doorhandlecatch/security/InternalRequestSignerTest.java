package com.ruanzhu.doorhandlecatch.security;

import com.ruanzhu.doorhandlecatch.config.properties.ChatAssistantProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InternalRequestSignerTest {

    @Test
    void signatureMatchesPythonCanonicalContract() {
        ChatAssistantProperties properties = new ChatAssistantProperties();
        properties.setInternalHmacSecret("shared-secret");
        InternalRequestSigner signer = new InternalRequestSigner(properties);

        String signature = signer.sign(
                "POST",
                "/internal/v1/agent-tools/ops/query",
                "1720000000",
                "nonce-1",
                "{\"x\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(signature).isEqualTo(
                "7dcd5604bf167cc76ef0a7fbb29d850b2b8067bd85a72359660d8f2b1d95167c");
    }
}
