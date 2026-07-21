package com.ruanzhu.doorhandlecatch.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.config.properties.ChatAssistantProperties;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonAgentInvokeRequest;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonAgentResponse;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonSkillInstallRequest;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonSkillListResponse;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonSkillRecord;
import com.ruanzhu.doorhandlecatch.security.InternalRequestSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PythonAssistantClientTest {

    private static final String SECRET = "python-assistant-contract-secret-32chars";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<Map<String, Object>> capturedRequest = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> capturedSkillRequest = new AtomicReference<>();
    private HttpServer server;
    private InternalRequestSigner signer;

    @BeforeEach
    void setUp() throws IOException {
        ChatAssistantProperties properties = properties();
        signer = new InternalRequestSigner(properties);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/v1/agent/invoke", this::handleInvoke);
        server.createContext("/internal/v1/skills/list", this::handleSkillList);
        server.createContext("/internal/v1/skills/install", this::handleSkillInstall);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void invokeSignsExactJsonBodyAndDeserializesPythonResponse() {
        ChatAssistantProperties properties = properties();
        properties.setPythonBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        PythonAssistantClient client = new PythonAssistantClient(
                properties, objectMapper, new InternalRequestSigner(properties));
        PythonAgentInvokeRequest request = PythonAgentInvokeRequest.builder()
                .requestId("req-contract-1")
                .idempotencyKey("idem-contract-1")
                .tenantUserId(42L)
                .username("alice")
                .sessionId("session-contract-1")
                .content("查询检测任务")
                .currentRoute("/quality")
                .currentPageTitle("质检中心")
                .build();

        PythonAgentResponse response = client.invoke(request);

        assertThat(capturedRequest.get())
                .containsEntry("request_id", "req-contract-1")
                .containsEntry("tenant_user_id", 42)
                .containsEntry("session_id", "session-contract-1")
                .containsEntry("content", "查询检测任务");
        assertThat(response.getRequestId()).isEqualTo("req-contract-1");
        assertThat(response.getContent()).isEqualTo("Harness 联调成功");
        assertThat(response.getIntent()).isEqualTo("DETECTION_QUERY");
        assertThat(response.getTrace()).containsExactly("context", "harness_deep_agent");
    }

    @Test
    void skillOperationsUseSignedInternalContractAndPreserveQuarantineStatus() {
        ChatAssistantProperties properties = properties();
        properties.setPythonBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        PythonAssistantClient client = new PythonAssistantClient(
                properties, objectMapper, new InternalRequestSigner(properties));

        PythonSkillListResponse list = client.listSkills();
        PythonSkillRecord installed = client.installSkill(PythonSkillInstallRequest.builder()
                .repository("openai/skills")
                .path("skills/.curated/demo-skill")
                .ref("main")
                .requestedBy("admin")
                .build());

        assertThat(list.isEnabled()).isTrue();
        assertThat(list.getSkills()).hasSize(1);
        assertThat(list.getSkills().get(0).getStatus()).isEqualTo("QUARANTINED");
        assertThat(capturedSkillRequest.get())
                .containsEntry("repository", "openai/skills")
                .containsEntry("path", "skills/.curated/demo-skill")
                .containsEntry("requested_by", "admin");
        assertThat(installed.getName()).isEqualTo("demo-skill");
        assertThat(installed.getChecksum()).hasSize(64);
    }

    private ChatAssistantProperties properties() {
        ChatAssistantProperties properties = new ChatAssistantProperties();
        properties.setInternalHmacSecret(SECRET);
        properties.setInternalSignatureMaxSkewSeconds(60);
        properties.setPythonConnectTimeoutMs(1000);
        properties.setPythonReadTimeoutMs(3000);
        return properties;
    }

    private void handleInvoke(HttpExchange exchange) throws IOException {
        byte[] body = verify(exchange);
        capturedRequest.set(objectMapper.readValue(body, new TypeReference<>() {}));
        respond(exchange, """
                {
                  "request_id": "req-contract-1",
                  "content": "Harness 联调成功",
                  "result_type": "TEXT",
                  "intent": "DETECTION_QUERY",
                  "checkpoint": {},
                  "exit_reason": "COMPLETE",
                  "trace": ["context", "harness_deep_agent"]
                }
                """);
    }

    private void handleSkillList(HttpExchange exchange) throws IOException {
        verify(exchange);
        respond(exchange, """
                {
                  "enabled": true,
                  "skills": [{
                    "name": "demo-skill",
                    "description": "测试 Skill",
                    "repository": "openai/skills",
                    "source_path": "skills/.curated/demo-skill",
                    "ref": "main",
                    "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "status": "QUARANTINED",
                    "installed_at": "2026-07-21T00:00:00+00:00",
                    "installed_by": "admin"
                  }]
                }
                """);
    }

    private void handleSkillInstall(HttpExchange exchange) throws IOException {
        byte[] body = verify(exchange);
        capturedSkillRequest.set(objectMapper.readValue(body, new TypeReference<>() {}));
        respond(exchange, """
                {
                  "name": "demo-skill",
                  "description": "测试 Skill",
                  "repository": "openai/skills",
                  "source_path": "skills/.curated/demo-skill",
                  "ref": "main",
                  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "status": "QUARANTINED",
                  "installed_at": "2026-07-21T00:00:00+00:00",
                  "installed_by": "admin"
                }
                """);
    }

    private byte[] verify(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        signer.verify(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("X-Timestamp"),
                exchange.getRequestHeaders().getFirst("X-Nonce"),
                exchange.getRequestHeaders().getFirst("X-Signature"),
                body);
        return body;
    }

    private void respond(HttpExchange exchange, String json) throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
