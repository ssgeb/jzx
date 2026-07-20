package com.ruanzhu.doorhandlecatch.dto.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
public class PythonAgentInvokeRequest {

    @JsonProperty("request_id")
    private String requestId;
    @JsonProperty("idempotency_key")
    private String idempotencyKey;
    @JsonProperty("tenant_user_id")
    private Long tenantUserId;
    private String username;
    @JsonProperty("session_id")
    private String sessionId;
    private String content;
    @JsonProperty("current_route")
    private String currentRoute;
    @JsonProperty("current_page_title")
    private String currentPageTitle;
    @Builder.Default
    private Map<String, Object> checkpoint = new LinkedHashMap<>();
    @Builder.Default
    private String mode = "MESSAGE";
}
