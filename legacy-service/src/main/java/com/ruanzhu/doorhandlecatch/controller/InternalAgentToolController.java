package com.ruanzhu.doorhandlecatch.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.common.Result;
import com.ruanzhu.doorhandlecatch.dto.chat.AgentExecutionResult;
import com.ruanzhu.doorhandlecatch.dto.internal.AgentToolRequest;
import com.ruanzhu.doorhandlecatch.security.InternalRequestSigner;
import com.ruanzhu.doorhandlecatch.security.TenantContext;
import com.ruanzhu.doorhandlecatch.service.ChatSessionService;
import com.ruanzhu.doorhandlecatch.service.AgentToolIdempotencyGuard;
import com.ruanzhu.doorhandlecatch.service.agent.DetectionAgentService;
import com.ruanzhu.doorhandlecatch.service.agent.OpsAgentService;
import com.ruanzhu.doorhandlecatch.service.agent.ReportAgentService;
import com.ruanzhu.doorhandlecatch.service.agent.ResourceAgentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/internal/v1/agent-tools")
@RequiredArgsConstructor
public class InternalAgentToolController {

    private final ObjectMapper objectMapper;
    private final InternalRequestSigner signer;
    private final ChatSessionService chatSessionService;
    private final DetectionAgentService detectionAgentService;
    private final ResourceAgentService resourceAgentService;
    private final ReportAgentService reportAgentService;
    private final OpsAgentService opsAgentService;
    private final AgentToolIdempotencyGuard idempotencyGuard;

    @PostMapping("/{agent}/{operation}")
    public Result<Map<String, Object>> execute(
            @PathVariable String agent,
            @PathVariable String operation,
            @RequestBody String rawBody,
            @RequestHeader("X-Timestamp") String timestamp,
            @RequestHeader("X-Nonce") String nonce,
            @RequestHeader("X-Signature") String signature,
            HttpServletRequest servletRequest) {
        signer.verify("POST", servletRequest.getRequestURI(), timestamp, nonce, signature,
                rawBody.getBytes(StandardCharsets.UTF_8));
        try {
            AgentToolRequest request = objectMapper.readValue(rawBody, AgentToolRequest.class);
            TenantContext tenant = requireTenantScope(request);
            String normalizedAgent = agent.toUpperCase(Locale.ROOT);
            String normalizedOperation = operation.toLowerCase(Locale.ROOT);
            AgentExecutionResult result;
            if ("action".equals(normalizedOperation)) {
                if (!StringUtils.hasText(request.getIdempotencyKey())) {
                    throw new BusinessException("写工具缺少幂等键");
                }
                String key = tenant.userId() + ":" + request.getSessionId() + ":"
                        + normalizedAgent + ":" + request.getIdempotencyKey();
                result = idempotencyGuard.executeOnce(
                        key,
                        () -> dispatch(normalizedAgent, normalizedOperation, request, tenant));
            } else {
                result = dispatch(normalizedAgent, normalizedOperation, request, tenant);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("content", result.getContent());
            data.put("resultType", result.getMessageType());
            data.put("intent", result.getIntent());
            return Result.success(data);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("执行智能体内部工具失败: " + ex.getMessage());
        }
    }

    private TenantContext requireTenantScope(AgentToolRequest request) {
        if (request.getTenantUserId() == null || request.getSessionId() == null || request.getUsername() == null) {
            throw new BusinessException("内部工具缺少租户上下文");
        }
        TenantContext actual = chatSessionService.resolveTenantForSystemCallback(request.getSessionId());
        if (!request.getTenantUserId().equals(actual.userId())
                || !request.getUsername().equals(actual.username())) {
            throw new BusinessException("内部工具租户上下文不匹配");
        }
        return actual;
    }

    private AgentExecutionResult dispatch(String agent, String operation,
                                          AgentToolRequest request, TenantContext tenant) {
        boolean action = "action".equals(operation);
        if (!action && !"query".equals(operation)) {
            throw new BusinessException("不支持的内部工具操作: " + operation);
        }
        return switch (agent) {
            case "DETECTION" -> action
                    ? detectionAgentService.executeConfirmedAction(request.getPrompt(), tenant, request.getSessionId())
                    : detectionAgentService.answer(request.getPrompt(), request.getUsername());
            case "RESOURCE" -> action
                    ? resourceAgentService.executeConfirmedAction(request.getPrompt(), request.getUsername())
                    : resourceAgentService.answer(request.getPrompt(), request.getUsername());
            case "REPORT" -> action
                    ? reportAgentService.executeConfirmedAction(request.getPrompt(), request.getUsername())
                    : reportAgentService.answer(request.getPrompt(), request.getUsername());
            case "OPS" -> {
                if (action) {
                    throw new BusinessException("OPS Agent 不允许执行写操作");
                }
                yield opsAgentService.answer(request.getPrompt(), request.getUsername());
            }
            default -> throw new BusinessException("不支持的内部 Agent: " + agent);
        };
    }
}
