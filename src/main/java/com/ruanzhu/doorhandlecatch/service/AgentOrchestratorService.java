package com.ruanzhu.doorhandlecatch.service;

import com.ruanzhu.doorhandlecatch.dto.chat.AgentRouteDecision;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatConfirmActionRequest;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatMessageResponse;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatSendMessageRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AgentOrchestratorService {

    AgentRouteDecision decideRoute(String content);

    ChatMessageResponse handleUserMessage(String username, ChatSendMessageRequest request);

    SseEmitter streamUserMessage(String username, ChatSendMessageRequest request);

    ChatMessageResponse confirmAction(String username, ChatConfirmActionRequest request);
}
