package com.ruanzhu.doorhandlecatch.dto.internal;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentToolRequest {
    private String idempotencyKey;
    private String requestId;
    private Long tenantUserId;
    private String username;
    private String sessionId;
    private String prompt;
    private String intent;
    private Map<String, Object> slots = new LinkedHashMap<>();
    private String currentRoute;
    private String currentPageTitle;
}
