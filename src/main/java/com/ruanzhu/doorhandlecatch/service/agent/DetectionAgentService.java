package com.ruanzhu.doorhandlecatch.service.agent;

import com.ruanzhu.doorhandlecatch.dto.chat.AgentExecutionResult;
import com.ruanzhu.doorhandlecatch.security.TenantContext;

import java.util.function.Consumer;

public interface DetectionAgentService {

    String previewAction(String userPrompt);

    AgentExecutionResult executeConfirmedAction(String userPrompt, TenantContext tenant, String sessionId);

    AgentExecutionResult answer(String userPrompt, String username);

    default AgentExecutionResult answer(String userPrompt, String username, Consumer<String> tokenConsumer) {
        return answer(userPrompt, username);
    }
}
