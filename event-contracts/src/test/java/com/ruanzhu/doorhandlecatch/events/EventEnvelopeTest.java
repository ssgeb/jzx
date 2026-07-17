package com.ruanzhu.doorhandlecatch.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventEnvelopeTest {

    @Test
    void requiresIdentityVersionTenantAndAggregate() {
        EventEnvelope event = new EventEnvelope(
                "evt-1", "resource.model.changed", 1,
                100L, "model-9", Instant.parse("2026-07-18T00:00:00Z"),
                Map.of("status", "ACTIVE"));

        assertThat(event.eventId()).isEqualTo("evt-1");
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.tenantId()).isEqualTo(100L);
        assertThat(event.aggregateId()).isEqualTo("model-9");
    }
}
