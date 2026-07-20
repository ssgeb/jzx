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
import com.ruanzhu.doorhandlecatch.dto.internal.PythonAgentAction;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonAgentInvokeRequest;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonAgentResponse;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonAgentResumeRequest;
import com.ruanzhu.doorhandlecatch.entity.ChatPendingAction;
import com.ruanzhu.doorhandlecatch.security.TenantContext;
import com.ruanzhu.doorhandlecatch.security.TenantPrincipal;
import com.ruanzhu.doorhandlecatch.service.AgentOrchestratorService;
import com.ruanzhu.doorhandlecatch.service.ChatSessionService;
import com.ruanzhu.doorhandlecatch.service.Mem0Client;
import com.ruanzhu.doorhandlecatch.service.PythonAssistantClient;
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
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final PythonAssistantClient pythonAssistantClient;
    private final Map<Long, RateWindow> requestWindows = new ConcurrentHashMap<>();

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
        TenantContext tenant = requireTenant(username);
        return handleUserMessageInternal(tenant, request, event -> {
        });
    }

    @Override
    public SseEmitter streamUserMessage(String username, ChatSendMessageRequest request) {
        TenantContext tenant = requireTenant(username);
        SseEmitter emitter = new SseEmitter(300_000L);
        Runnable streamTask = () -> {
            try {
                AtomicBoolean streamedContent = new AtomicBoolean(false);
                sendStreamEvent(emitter, "connected", ChatStreamEvent.status(request.getSessionId(), "已连接智能助手流式通道"));
                ChatMessageResponse response = handleUserMessageInternal(
                        tenant,
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
        };
        taskExecutor.execute(new DelegatingSecurityContextRunnable(
                streamTask,
                SecurityContextHolder.getContext()));
        return emitter;
    }

    private ChatMessageResponse handleUserMessageInternal(TenantContext tenant, ChatSendMessageRequest request,
                                                          Consumer<ChatStreamEvent> progressConsumer) {
        return handleUserMessageInternal(tenant, request, progressConsumer, null);
    }

    private ChatMessageResponse handleUserMessageInternal(TenantContext tenant,
                                                          ChatSendMessageRequest request,
                                                          Consumer<ChatStreamEvent> progressConsumer,
                                                          Consumer<String> tokenConsumer) {
        String username = tenant.username();
        enforceRateLimit(tenant.userId());

        // 1. 确保会话存在
        if (!StringUtils.hasText(request.getSessionId())) {
            request.setSessionId(chatSessionService.getOrCreateActiveSession(username).getSessionId());
        } else {
            chatSessionService.verifySessionOwner(username, request.getSessionId());
        }
        String sessionId = request.getSessionId();
        progressConsumer.accept(ChatStreamEvent.status(sessionId, "会话已准备"));

        // 2. 保存用户消息到数据库
        ChatMessageResponse userMessage = chatSessionService.appendUserMessage(
                tenant, sessionId, request.getContent());
        progressConsumer.accept(ChatStreamEvent.status(sessionId, "用户消息已保存"));

        // Python 模式复用原有 Java 会话、消息和 Checkpoint 边界。
        // Python 调用失败时只允许在初次消息的只读/待确认阶段回退 Java；确认执行不走这里。
        if (isPythonEngine()) {
            try {
                return handlePythonUserMessage(tenant, request, userMessage, progressConsumer);
            } catch (RuntimeException ex) {
                if (!Boolean.TRUE.equals(chatAssistantProperties.getFallbackToJava())) {
                    throw ex;
                }
                log.warn("Python 智能体调用失败，回退 Java StateGraph: {}", ex.getMessage());
                progressConsumer.accept(ChatStreamEvent.status(sessionId, "Python 智能体暂不可用，已切换备用引擎"));
            }
        }

        // 3. 从 checkpoint 恢复多轮上下文，构建新 State
        AgentState state = buildStateWithContext(tenant, sessionId, request);
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

        List<Map<String, Object>> userMemories = mem0Client.searchMemories(
                tenant, sessionId, request.getContent(), 5);
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
        resultContent = appendKnowledgeSourceIfNeeded(resultContent, ragContext);
        if (StringUtils.hasText(resultContent)) {
            StateUpdater.appendMessage(result, "assistant", resultContent, maxRecentMessages);
            // 保存更新后的 state（包含本轮消息），但 invoke 后 checkpoint 已被 runLoop 保存，
            // 此处需再次保存以包含 assistant 消息
            saveContextState(tenant, sessionId, result);
        }

        // 6.5 异步存储对话记忆（不阻塞主流程）
        storeMemoryAsync(tenant, sessionId, request.getContent(), resultContent);

        // 7. 处理结果
        String exitReason = result.getString(AgentState.KEY_EXIT_REASON);

        if (AgentState.EXIT_PENDING_CONFIRMATION.equals(exitReason)) {
            return chatSessionService.appendAssistantMessage(
                    tenant, sessionId,
                    resultContent,
                    result.getString(AgentState.KEY_RESULT_TYPE),
                    result.getString(AgentState.KEY_INTENT),
                    result.getString(AgentState.KEY_PENDING_ACTION_ID)
            );
        }

        return chatSessionService.appendAssistantMessage(
                tenant, sessionId,
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
        TenantContext tenant = requireTenant(username);
        chatSessionService.verifySessionOwner(username, request.getSessionId());
        ChatPendingAction action = chatSessionService.getPendingAction(tenant, request.getSessionId(), request.getActionId());
        ChatPendingActionPayload payload = readPayload(action.getActionPayloadJson());
        String claimedStatus = request.isConfirmed() ? "EXECUTING" : "CANCELLED";
        boolean claimed = chatSessionService.transitionPendingAction(
                tenant, request.getSessionId(), request.getActionId(), "PENDING", claimedStatus, null);
        if (!claimed) {
            throw new BusinessException("待确认动作正在处理或已处理，请勿重复提交");
        }

        if (!request.isConfirmed()) {
            return chatSessionService.appendAssistantMessage(
                    tenant, request.getSessionId(),
                    "已取消这次操作。如果你愿意，我可以继续帮你改成查询、总结，或者换一种执行方式。",
                    "TEXT",
                    payload.getIntent(),
                    null
            );
        }

        try {
            if (isPythonEngine()) {
                return handlePythonConfirmation(tenant, request);
            }
            AgentState result = chatGraph.resume(tenant, request.getSessionId(), Map.of(
                    AgentState.KEY_CONFIRMED, true
            ));

            String resultContent = result.getString(AgentState.KEY_RESULT_CONTENT);
            if (StringUtils.hasText(resultContent)) {
                StateUpdater.appendMessage(result, "assistant", resultContent, maxRecentMessages);
                saveContextState(tenant, request.getSessionId(), result);
            }

            ChatMessageResponse response = chatSessionService.appendAssistantMessage(
                    tenant, request.getSessionId(),
                    resultContent,
                    result.getString(AgentState.KEY_RESULT_TYPE),
                    result.getString(AgentState.KEY_INTENT),
                    null
            );
            chatSessionService.transitionPendingAction(
                    tenant, request.getSessionId(), request.getActionId(), "EXECUTING", "COMPLETED", null);
            return response;
        } catch (RuntimeException ex) {
            chatSessionService.transitionPendingAction(
                    tenant, request.getSessionId(), request.getActionId(), "EXECUTING", "FAILED",
                    abbreviateError(ex.getMessage()));
            throw ex;
        }
    }

    private ChatMessageResponse handlePythonUserMessage(
            TenantContext tenant,
            ChatSendMessageRequest request,
            ChatMessageResponse userMessage,
            Consumer<ChatStreamEvent> progressConsumer) {
        String requestId = UUID.randomUUID().toString();
        String idempotencyKey = userMessage != null && userMessage.getId() != null
                ? "chat-message:" + userMessage.getId()
                : "chat-request:" + requestId;
        PythonAgentInvokeRequest pythonRequest = PythonAgentInvokeRequest.builder()
                .requestId(requestId)
                .idempotencyKey(idempotencyKey)
                .tenantUserId(tenant.userId())
                .username(tenant.username())
                .sessionId(request.getSessionId())
                .content(request.getContent())
                .currentRoute(request.getCurrentRoute())
                .currentPageTitle(request.getCurrentPageTitle())
                .checkpoint(loadCheckpointMap(tenant, request.getSessionId()))
                .build();
        progressConsumer.accept(ChatStreamEvent.status(request.getSessionId(), "正在调用 Python 智能体"));
        PythonAgentResponse response = pythonAssistantClient.invoke(pythonRequest);
        progressConsumer.accept(ChatStreamEvent.status(request.getSessionId(), "Python 智能体回答生成完成"));
        return persistPythonResponse(tenant, request.getSessionId(), request.getContent(), response);
    }

    private ChatMessageResponse handlePythonConfirmation(
            TenantContext tenant,
            ChatConfirmActionRequest request) {
        PythonAgentResumeRequest pythonRequest = PythonAgentResumeRequest.builder()
                .requestId(UUID.randomUUID().toString())
                .idempotencyKey("chat-action:" + request.getActionId())
                .tenantUserId(tenant.userId())
                .username(tenant.username())
                .sessionId(request.getSessionId())
                .actionId(request.getActionId())
                .confirmed(true)
                .checkpoint(loadCheckpointMap(tenant, request.getSessionId()))
                .build();
        PythonAgentResponse response = pythonAssistantClient.resume(pythonRequest);
        return persistPythonResponse(tenant, request.getSessionId(), null, response);
    }

    private ChatMessageResponse persistPythonResponse(
            TenantContext tenant,
            String sessionId,
            String userPrompt,
            PythonAgentResponse response) {
        if (response == null || !StringUtils.hasText(response.getContent())) {
            throw new BusinessException("Python 智能体返回空结果");
        }
        AgentState state = AgentState.fromMap(response.getCheckpoint());
        state.set(AgentState.KEY_THREAD_ID, sessionId)
                .set(AgentState.KEY_TENANT_USER_ID, tenant.userId())
                .set(AgentState.KEY_USERNAME, tenant.username())
                .set(AgentState.KEY_RESULT_CONTENT, response.getContent())
                .set(AgentState.KEY_RESULT_TYPE, response.getResultType())
                .set(AgentState.KEY_INTENT, response.getIntent())
                .set(AgentState.KEY_EXIT_REASON, response.getExitReason());

        String actionId = null;
        if (AgentState.EXIT_PENDING_CONFIRMATION.equals(response.getExitReason())) {
            PythonAgentAction action = response.getAction();
            if (action == null || !StringUtils.hasText(action.getActionId())) {
                throw new BusinessException("Python 智能体待确认结果缺少 actionId");
            }
            actionId = action.getActionId();
            state.set(AgentState.KEY_PENDING_ACTION_ID, actionId);
            ChatPendingActionPayload payload = new ChatPendingActionPayload();
            payload.setUsername(tenant.username());
            payload.setUserPrompt(StringUtils.hasText(action.getTaskPrompt())
                    ? action.getTaskPrompt()
                    : userPrompt);
            payload.setIntent(action.getIntent());
            payload.setTargetAgent(action.getTargetAgent());
            chatSessionService.savePendingAction(
                    tenant, sessionId, actionId, action.getIntent(), payload);
        }

        checkpointer.save(tenant, sessionId, state);
        String content = stripEmoji(response.getContent());
        return chatSessionService.appendAssistantMessage(
                tenant,
                sessionId,
                content,
                response.getResultType(),
                response.getIntent(),
                actionId);
    }

    private Map<String, Object> loadCheckpointMap(TenantContext tenant, String sessionId) {
        AgentState previous = checkpointer.load(tenant, sessionId);
        return previous == null ? Map.of() : previous.toMap();
    }

    private boolean isPythonEngine() {
        return "python".equalsIgnoreCase(chatAssistantProperties.getEngine());
    }

    // ---- 上下文恢复 ----

    /** 从 checkpoint 恢复多轮上下文，合并到新 State 中 */
    private AgentState buildStateWithContext(TenantContext tenant, String sessionId,
                                             ChatSendMessageRequest request) {
        AgentState state = AgentState.create(sessionId, request.getContent(), tenant.username())
                .set(AgentState.KEY_TENANT_USER_ID, tenant.userId());
        if (StringUtils.hasText(request.getCurrentRoute())) {
            state.set(AgentState.KEY_CURRENT_ROUTE, request.getCurrentRoute());
        }
        if (StringUtils.hasText(request.getCurrentPageTitle())) {
            state.set(AgentState.KEY_CURRENT_PAGE_TITLE, request.getCurrentPageTitle());
        }

        try {
            AgentState previous = checkpointer.load(tenant, sessionId);
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
    private void saveContextState(TenantContext tenant, String sessionId, AgentState state) {
        try {
            checkpointer.save(tenant, sessionId, state);
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
    private void storeMemoryAsync(TenantContext tenant, String sessionId,
                                  String userMessage, String assistantReply) {
        try {
            // 构建对话内容用于记忆提取
            String conversationContent = String.format("用户: %s\n助手: %s", userMessage, assistantReply);

            // 构建元数据
            Map<String, Object> metadata = Map.of(
                    "source", "chat",
                    "timestamp", System.currentTimeMillis()
            );

            // 异步存储，不阻塞主流程
            mem0Client.addMemoryAsync(tenant, sessionId, conversationContent, metadata);
        } catch (Exception e) {
            log.warn("异步存储记忆失败: {}", e.getMessage());
        }
    }

    private void enforceRateLimit(Long userId) {
        int maxRequests = Math.max(1, chatAssistantProperties.getMaxRequestsPerMinute() == null
                ? 30
                : chatAssistantProperties.getMaxRequestsPerMinute());
        long now = System.currentTimeMillis();
        RateWindow window = requestWindows.compute(userId, (key, existing) -> {
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

    private TenantContext requireTenant(String expectedUsername) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof TenantPrincipal principal)) {
            throw new BusinessException(401, "登录身份缺少租户信息");
        }
        if (!principal.username().equals(expectedUsername)) {
            throw new BusinessException(403, "登录身份与请求用户不一致");
        }
        return principal.tenantContext();
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

    private String appendKnowledgeSourceIfNeeded(String content, String ragContext) {
        if (!StringUtils.hasText(content) || !StringUtils.hasText(ragContext)) {
            return content;
        }
        if (content.contains("来源：") || content.contains("来源:")) {
            return content;
        }
        return content.stripTrailing() + "\n\n来源：系统知识库/用户手册";
    }

    private String abbreviateError(String message) {
        String value = StringUtils.hasText(message) ? message.trim() : "未知执行错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
