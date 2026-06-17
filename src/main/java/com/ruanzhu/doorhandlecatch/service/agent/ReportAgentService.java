package com.ruanzhu.doorhandlecatch.service.agent;

import com.ruanzhu.doorhandlecatch.dto.chat.AgentExecutionResult;

import java.util.function.Consumer;

public interface ReportAgentService {

    String previewAction(String userPrompt);

    AgentExecutionResult answer(String userPrompt, String username);

    default AgentExecutionResult answer(String userPrompt, String username, Consumer<String> tokenConsumer) {
        return answer(userPrompt, username);
    }

    AgentExecutionResult executeConfirmedAction(String userPrompt, String username);
}
