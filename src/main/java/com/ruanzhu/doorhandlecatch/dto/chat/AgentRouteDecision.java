package com.ruanzhu.doorhandlecatch.dto.chat;

import lombok.Data;

@Data
public class AgentRouteDecision {

    private String intent;
    private String targetAgent;
    private boolean confirmationRequired;
    private String normalizedUserPrompt;
}
