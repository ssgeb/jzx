package com.ruanzhu.doorhandlecatch.events;

import java.time.Instant;
import java.util.Map;

public record EventEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        Long tenantId,
        String aggregateId,
        Instant occurredAt,
        Map<String, Object> payload) {

    public EventEnvelope {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType 不能为空");
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion 必须大于 0");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId 不能为空");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 不能为空");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
