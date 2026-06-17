package com.ruanzhu.doorhandlecatch.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.config.properties.ChatAssistantProperties;
import com.ruanzhu.doorhandlecatch.dto.chat.AgentRouteDecision;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatConfirmActionRequest;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatMessageResponse;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatPendingActionPayload;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatSendMessageRequest;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatStreamEvent;
import com.ruanzhu.doorhandlecatch.entity.ChatPendingAction;
import com.ruanzhu.doorhandlecatch.service.AgentOrchestratorService;
import com.ruanzhu.doorhandlecatch.service.ChatSessionService;
import com.ruanzhu.doorhandlecatch.service.Mem0Client;
import com.ruanzhu.doorhandlecatch.service.RagKnowledgeService;
import com.ruanzhu.doorhandlecatch.stategraph.checkpoint.MySqlCheckpointer;
import com.ruanzhu.doorhandlecatch.stategraph.core.AgentState;
import com.ruanzhu.doorhandlecatch.stategraph.core.CompiledGraph;
import com.ruanzhu.doorhandlecatch.stategraph.node.RouterNode;
import com.ruanzhu.doorhandlecatch.stategraph.util.StateUpdater;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 多 Agent 编排服务 — 基于 StateGraph 实现。
 * 支持多轮对话：从 checkpoint 恢复上下文（slots + messages + summary）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestratorServiceImpl implements AgentOrchestratorService {

    private final ChatSessionService chatSessionService;
    private final CompiledGraph chatGraph;
    private final RouterNode routerNode;
    private final MySqlCheckpointer checkpointer;
    private final ObjectMapper objectMapper;
    private final Mem0Client mem0Client;
    private final RagKnowledgeService ragKnowledgeService;
    private final TaskExecutor taskExecutor;
    private final ChatAssistantProperties chatAssistantProperties;
    private final Map<String, RateWindow> requestWindows = new ConcurrentHashMap<>();

    @Value("${conversation.max-recent-messages:10}")
    private int maxRecentMessages;

    /** 多轮上下文字段 key 列表 — 从 checkpoint 恢复到新 state 时保留 */
    private static final List<String> CONTEXT_KEYS = List.of(
            AgentState.KEY_CONVERSATION_TURN,
            AgentState.KEY_RECENT_MESSAGES,
            AgentState.KEY_CONVERSATION_SUMMARY,
            AgentState.KEY_TASK_TYPE,
            AgentState.KEY_SLOTS,
            AgentState.KEY_MISSING_SLOTS,
            AgentState.KEY_INTERMEDIATE_RESULTS,
            AgentState.KEY_CONVERSATION_PHASE,
            AgentState.KEY_ROUTE_DECISION,
            AgentState.KEY_INTENT
    );

    @Override
    public AgentRouteDecision decideRoute(String content) {
        AgentState state = AgentState.create("_route_", content, "_system_");
        state = routerNode.execute(state);
        return state.get(AgentState.KEY_ROUTE_DECISION, AgentRouteDecision.class);
    }

    @Override
    public ChatMessageResponse handleUserMessage(String username, ChatSendMessageRequest request) {
        return handleUserMessageInternal(username, request, event -> {
        });
    }

    @Override
    public SseEmitter streamUserMessage(String username, ChatSendMessageRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        taskExecutor.execute(() -> {
            try {
                AtomicBoolean streamedContent = new AtomicBoolean(false);
                sendStreamEvent(emitter, "connected", ChatStreamEvent.status(request.getSessionId(), "已连接智能助手流式通道"));
                ChatMessageResponse response = handleUserMessageInternal(
                        username,
                        request,
                        event -> sendStreamEvent(emitter, event.getType(), event),
                        token -> {
                            streamedContent.set(true);
                            sendStreamEvent(emitter, "chunk", ChatStreamEvent.builder()
                                    .type("chunk")
                                    .sessionId(request.getSessionId())
                                    .content(token)
                                    .build());
                        }
                );
                boolean textMessage = "TEXT".equals(response.getMessageType()) || !StringUtils.hasText(response.getMessageType());
                if (!streamedContent.get() && textMessage) {
                    streamContentChunks(emitter, response.getSessionId(), response.getContent());
                } else if (!textMessage) {
                    sendStreamEvent(emitter, "status", ChatStreamEvent.status(response.getSessionId(), "已生成结构化业务卡片"));
                }
                sendStreamEvent(emitter, "done", ChatStreamEvent.builder()
                        .type("done")
                        .sessionId(response.getSessionId())
                        .intent(response.getIntent())
                        .actionId(response.getActionId())
                        .messageResponse(response)
                        .build());
                emitter.complete();
            } catch (Exception ex) {
                log.warn("流式聊天响应失败: {}", ex.getMessage(), ex);
                sendStreamEvent(emitter, "error", ChatStreamEvent.builder()
                        .type("error")
                        .sessionId(request.getSessionId())
                        .message(ex.getMessage())
                        .build());
                // SSE 已经把错误事件写给前端，正常结束可避免容器再次转发 /error 导致响应已提交后的二次异常。
                emitter.complete();
            }
        });
        return emitter;
    }

    private ChatMessageResponse handleUserMessageInternal(String username, ChatSendMessageRequest request,
                                                          Consumer<ChatStreamEvent> progressConsumer) {
        return handleUserMessageInternal(username, request, progressConsumer, null);
    }

    private ChatMessageResponse handleUserMessageInternal(String username,
                                                          ChatSendMessageRequest request,
                                                          Consumer<ChatStreamEvent> progressConsumer,
                                                          Consumer<String> tokenConsumer) {
        enforceRateLimit(username);

        // 1. 确保会话存在
        if (!StringUtils.hasText(request.getSessionId())) {
            request.setSessionId(chatSessionService.getOrCreateActiveSession(username).getSessionId());
        } else {
            chatSessionService.verifySessionOwner(username, request.getSessionId());
        }
        String sessionId = request.getSessionId();
        progressConsumer.accept(ChatStreamEvent.status(sessionId, "会话已准备"));

        // 2. 保存用户消息到数据库
        chatSessionService.appendUserMessage(sessionId, request.getContent());
        progressConsumer.accept(ChatStreamEvent.status(sessionId, "用户消息已保存"));

        // 3. 从 checkpoint 恢复多轮上下文，构建新 State
        AgentState state = buildStateWithContext(sessionId, request, username);
        if (tokenConsumer != null) {
            state.set(AgentState.KEY_STREAM_CONSUMER, tokenConsumer);
        }
        progressConsumer.accept(ChatStreamEvent.status(sessionId, "已恢复多轮上下文"));

        // 3.5 检索系统知识库和用户长期记忆并注入到 state
        String ragContext = ragKnowledgeService.retrieveContext(request.getContent());
        if (StringUtils.hasText(ragContext)) {
            state.set(AgentState.KEY_RAG_CONTEXT, ragContext);
            log.debug("已注入 RAG 知识库上下文: {} chars", ragContext.length());
        }
        progressConsumer.accept(ChatStreamEvent.status(sessionId, StringUtils.hasText(ragContext)
                ? "已检索系统知识库，正在检索记忆"
                : "系统知识库无强相关片段，正在检索记忆"));

        List<Map<String, Object>> userMemories = mem0Client.searchMemories(username, request.getContent(), 5);
        if (userMemories != null && !userMemories.isEmpty()) {
            state.set("user_memories", userMemories);
            String memoryContext = mem0Client.formatMemoriesAsContext(userMemories);
            state.set("user_memory_context", memoryContext);
            log.debug("已注入 {} 条用户记忆到上下文", userMemories.size());
        }
        progressConsumer.accept(ChatStreamEvent.status(sessionId, "已完成记忆检索，正在生成回答"));

        // 4. 追加用户消息到 recentMessages
        StateUpdater.appendMessage(state, "user", request.getContent(), maxRecentMessages);

        // 5. 执行图
        AgentState result = chatGraph.invoke(state);
        progressConsumer.accept(ChatStreamEvent.status(sessionId, "回答生成完成，正在写入会话"));

        // 6. 追加助手消息到 recentMessages（供下一轮上下文使用）
        String resultContent = stripEmoji(result.getString(AgentState.KEY_RESULT_CONTENT));
        if (StringUtils.hasText(resultContent)) {
            StateUpdater.appendMessage(result, "assistant", resultContent, maxRecentMessages);
            // 保存更新后的 state（包含本轮消息），但 invoke 后 checkpoint 已被 runLoop 保存，
            // 此处需再次保存以包含 assistant 消息
            saveContextState(sessionId, result);
        }

        // 6.5 异步存储对话记忆（不阻塞主流程）
        storeMemoryAsync(username, request.getContent(), resultContent);

        // 7. 处理结果
        String exitReason = result.getString(AgentState.KEY_EXIT_REASON);

        if (AgentState.EXIT_PENDING_CONFIRMATION.equals(exitReason)) {
            return chatSessionService.appendAssistantMessage(
                    sessionId,
                    resultContent,
                    result.getString(AgentState.KEY_RESULT_TYPE),
                    result.getString(AgentState.KEY_INTENT),
                    result.getString(AgentState.KEY_PENDING_ACTION_ID)
            );
        }

        return chatSessionService.appendAssistantMessage(
                sessionId,
                resultContent,
                result.getString(AgentState.KEY_RESULT_TYPE),
                result.getString(AgentState.KEY_INTENT),
                null
        );
    }

    private void streamContentChunks(SseEmitter emitter, String sessionId, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        int index = 0;
        int chunkSize = 12;
        while (index < content.length()) {
            int end = Math.min(content.length(), index + chunkSize);
            String chunk = content.substring(index, end);
            sendStreamEvent(emitter, "chunk", ChatStreamEvent.builder()
                    .type("chunk")
                    .sessionId(sessionId)
                    .content(chunk)
                    .build());
            index = end;
        }
    }

    private void sendStreamEvent(SseEmitter emitter, String eventName, ChatStreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(event));
        } catch (Exception ex) {
            throw new BusinessException("发送聊天流事件失败: " + ex.getMessage());
        }
    }

    @Override
    public ChatMessageResponse confirmAction(String username, ChatConfirmActionRequest request) {
        chatSessionService.verifySessionOwner(username, request.getSessionId());
        ChatPendingAction action = chatSessionService.getPendingAction(request.getSessionId(), request.getActionId());
        ChatPendingActionPayload payload = readPayload(action.getActionPayloadJson());

        if (!request.isConfirmed()) {
            chatSessionService.markPendingActionStatus(request.getSessionId(), action.getActionId(), "CANCELLED");
            return chatSessionService.appendAssistantMessage(
                    request.getSessionId(),
                    "已取消这次操作。如果你愿意，我可以继续帮你改成查询、总结，或者换一种执行方式。",
                    "TEXT",
                    payload.getIntent(),
                    null
            );
        }

        chatSessionService.markPendingActionStatus(request.getSessionId(), action.getActionId(), "CONFIRMED");

        AgentState result = chatGraph.resume(request.getSessionId(), Map.of(
                AgentState.KEY_CONFIRMED, true
        ));

        // 追加助手消息到上下文
        String resultContent = result.getString(AgentState.KEY_RESULT_CONTENT);
        if (StringUtils.hasText(resultContent)) {
            StateUpdater.appendMessage(result, "assistant", resultContent, maxRecentMessages);
            saveContextState(request.getSessionId(), result);
        }

        return chatSessionService.appendAssistantMessage(
                request.getSessionId(),
                resultContent,
                result.getString(AgentState.KEY_RESULT_TYPE),
                result.getString(AgentState.KEY_INTENT),
                null
        );
    }

    // ---- 上下文恢复 ----

    /** 从 checkpoint 恢复多轮上下文，合并到新 State 中 */
    private AgentState buildStateWithContext(String sessionId, ChatSendMessageRequest request, String username) {
        AgentState state = AgentState.create(sessionId, request.getContent(), username);
        if (StringUtils.hasText(request.getCurrentRoute())) {
            state.set(AgentState.KEY_CURRENT_ROUTE, request.getCurrentRoute());
        }
        if (StringUtils.hasText(request.getCurrentPageTitle())) {
            state.set(AgentState.KEY_CURRENT_PAGE_TITLE, request.getCurrentPageTitle());
        }

        try {
            AgentState previous = checkpointer.load(sessionId);
            if (previous != null) {
                for (String key : CONTEXT_KEYS) {
                    Object value = previous.get(key, Object.class);
                    if (value != null) {
                        state.set(key, value);
                    }
                }
                log.debug("已恢复多轮上下文: sessionId={} turn={} slots={} phase={}",
                        sessionId,
                        state.getInt(AgentState.KEY_CONVERSATION_TURN),
                        state.getSlots(),
                        state.getString(AgentState.KEY_CONVERSATION_PHASE));
            }
        } catch (Exception e) {
            log.warn("恢复 checkpoint 失败，使用全新 state: {}", e.getMessage());
        }

        return state;
    }

    /** 将当前 state 的上下文字段保存到 checkpoint */
    private void saveContextState(String sessionId, AgentState state) {
        try {
            checkpointer.save(sessionId, state);
        } catch (Exception e) {
            log.warn("保存上下文 state 失败: {}", e.getMessage());
        }
    }

    private ChatPendingActionPayload readPayload(String json) {
        try {
            return objectMapper.readValue(json, ChatPendingActionPayload.class);
        } catch (Exception e) {
            throw new BusinessException("待确认动作解析失败");
        }
    }

    /**
     * 异步存储对话记忆
     * 从用户消息和助手回复中提取有价值的信息存储到长期记忆
     */
    private void storeMemoryAsync(String username, String userMessage, String assistantReply) {
        try {
            // 构建对话内容用于记忆提取
            String conversationContent = String.format("用户: %s\n助手: %s", userMessage, assistantReply);

            // 构建元数据
            Map<String, Object> metadata = Map.of(
                    "source", "chat",
                    "timestamp", System.currentTimeMillis()
            );

            // 异步存储，不阻塞主流程
            mem0Client.addMemoryAsync(username, conversationContent, metadata);
        } catch (Exception e) {
            log.warn("异步存储记忆失败: {}", e.getMessage());
        }
    }

    private void enforceRateLimit(String username) {
        int maxRequests = Math.max(1, chatAssistantProperties.getMaxRequestsPerMinute() == null
                ? 30
                : chatAssistantProperties.getMaxRequestsPerMinute());
        long now = System.currentTimeMillis();
        RateWindow window = requestWindows.compute(username, (key, existing) -> {
            if (existing == null || now - existing.windowStartedAt >= 60_000L) {
                return new RateWindow(now, 1);
            }
            existing.count++;
            return existing;
        });
        if (window.count > maxRequests) {
            throw new BusinessException("聊天请求过于频繁，请稍后再试");
        }
    }

    private static final class RateWindow {
        private final long windowStartedAt;
        private int count;

        private RateWindow(long windowStartedAt, int count) {
            this.windowStartedAt = windowStartedAt;
            this.count = count;
        }
    }

    /**
     * 过滤 emoji 和特殊 Unicode 字符，避免 MySQL utf8 存储异常
     */
    private String stripEmoji(String text) {
        if (text == null) return null;
        // 移除 4 字节 UTF-8 字符（emoji 及部分 CJK 扩展字符）
        return text.replaceAll("[\\x{10000}-\\x{10FFFF}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]", "");
    }
}
