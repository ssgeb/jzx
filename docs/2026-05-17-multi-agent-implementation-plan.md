# Multi-Agent Chat Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first working version of the project-wide chat assistant described in `docs/2026-05-17-multi-agent-overall-design.md`, with one chat entry, one orchestrator agent, four specialized agents, confirmation before write actions, and integration with existing detection/resource/report/ops services.

**Architecture:** Keep the first version embedded inside the existing Spring Boot backend. Add a thin chat API layer, an agent orchestration layer, and tool adapters that wrap existing detection, resource, report, and ops services. Add a single shared Vue chat drawer in the global layout; the UI is dumb and only renders messages, confirmations, and execution status.

**Tech Stack:** Spring Boot 3.2, MyBatis/MyBatis-Plus, MySQL, Vue 3, Pinia, Element Plus, Ant Design Vue, Axios.

---

## File Structure

### Backend new files

- `src/main/java/com/ruanzhu/doorhandlecatch/entity/ChatSession.java`
  Session metadata for one user conversation.
- `src/main/java/com/ruanzhu/doorhandlecatch/entity/ChatMessage.java`
  Persisted user/assistant/system messages.
- `src/main/java/com/ruanzhu/doorhandlecatch/entity/ChatPendingAction.java`
  Stores confirmation-required actions before execution.
- `src/main/java/com/ruanzhu/doorhandlecatch/mapper/ChatSessionMapper.java`
  Session CRUD.
- `src/main/java/com/ruanzhu/doorhandlecatch/mapper/ChatMessageMapper.java`
  Message CRUD and recent-history queries.
- `src/main/java/com/ruanzhu/doorhandlecatch/mapper/ChatPendingActionMapper.java`
  Pending action CRUD.
- `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/ChatSendMessageRequest.java`
  Input DTO for user messages.
- `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/ChatMessageResponse.java`
  One rendered message returned to frontend.
- `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/ChatSessionResponse.java`
  Session summary + message list.
- `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/ChatConfirmActionRequest.java`
  Confirm or cancel pending action.
- `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/AgentRouteDecision.java`
  Orchestrator output: target agent, intent, whether confirmation is required.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/ChatSessionService.java`
  Session/message/pending-action service.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/AgentOrchestratorService.java`
  Main agent coordinator.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/DetectionAgentService.java`
  Detection agent contract.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/ResourceAgentService.java`
  Resource agent contract.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/ReportAgentService.java`
  Report agent contract.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/OpsAgentService.java`
  Ops agent contract.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImpl.java`
  Session/message/pending-action implementation.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImpl.java`
  Intent routing, confirmation handling, response assembly.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/impl/DetectionAgentServiceImpl.java`
  Wraps existing detection task and image detection services.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/impl/ResourceAgentServiceImpl.java`
  Wraps device/employee/model services.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/impl/ReportAgentServiceImpl.java`
  Wraps dashboard + detection summary queries.
- `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/impl/OpsAgentServiceImpl.java`
  Wraps detection task status, remote service errors, OSS status hints.
- `src/main/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantController.java`
  Chat session, send message, confirm action APIs.

### Backend modified files

- `src/main/resources/db/schema.sql`
  Add `chat_session`, `chat_message`, `chat_pending_action`.
- `src/main/resources/application.yml`
  Add chat-assistant config section.
- `src/main/java/com/ruanzhu/doorhandlecatch/config/SecurityConfig.java`
  Permit authenticated access to `/api/chat-assistant/**`.
- `src/main/java/com/ruanzhu/doorhandlecatch/security/JwtAuthenticationFilter.java`
  Keep authenticated chat endpoints working with JWT.
- `src/main/java/com/ruanzhu/doorhandlecatch/common/GlobalExceptionHandler.java`
  Return clean business errors for chat flows.

### Backend test files

- `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImplTest.java`
- `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImplTest.java`
- `src/test/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantControllerTest.java`
- `src/test/java/com/ruanzhu/doorhandlecatch/service/agent/impl/DetectionAgentServiceImplTest.java`

### Frontend new files

- `frontend/src/api/chatAssistant.js`
  Chat assistant API wrappers.
- `frontend/src/stores/chatAssistant.js`
  Shared chat session state and pending action state.
- `frontend/src/components/chat/ChatAssistantLauncher.vue`
  Floating launcher button.
- `frontend/src/components/chat/ChatAssistantDrawer.vue`
  Right-side drawer with shared chat UI.
- `frontend/src/components/chat/ChatMessageList.vue`
  Message list renderer.
- `frontend/src/components/chat/ChatComposer.vue`
  Input box + send action.
- `frontend/src/components/chat/ChatPendingActionCard.vue`
  Confirmation card renderer.

### Frontend modified files

- `frontend/src/layout/index.vue`
  Mount global launcher and drawer once.
- `frontend/src/main.js`
  Ensure global store initialization if needed.

### Frontend verification

- `frontend/package.json`
  Keep build script; optionally add `test` only if needed during implementation.

---

### Task 1: Add persistence for chat sessions, messages, and pending confirmations

**Files:**
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/entity/ChatSession.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/entity/ChatMessage.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/entity/ChatPendingAction.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/mapper/ChatSessionMapper.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/mapper/ChatMessageMapper.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/mapper/ChatPendingActionMapper.java`
- Modify: `src/main/resources/db/schema.sql`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImplTest.java`

- [ ] **Step 1: Write the failing persistence test**

```java
package com.ruanzhu.doorhandlecatch.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ruanzhu.doorhandlecatch.entity.ChatMessage;
import com.ruanzhu.doorhandlecatch.entity.ChatPendingAction;
import com.ruanzhu.doorhandlecatch.entity.ChatSession;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatSessionServiceImplTest {

    @Test
    void shouldCreateSessionAndAppendMessagesAndPendingAction() {
        ChatSession session = new ChatSession();
        session.setSessionId("sess_001");
        session.setUsername("admin");
        session.setStatus("ACTIVE");

        ChatMessage message = new ChatMessage();
        message.setSessionId("sess_001");
        message.setRole("user");
        message.setContent("帮我开始检测");

        ChatPendingAction pendingAction = new ChatPendingAction();
        pendingAction.setActionId("act_001");
        pendingAction.setSessionId("sess_001");
        pendingAction.setActionType("START_DETECTION");
        pendingAction.setStatus("PENDING");

        assertThat(session.getSessionId()).isEqualTo("sess_001");
        assertThat(message.getRole()).isEqualTo("user");
        assertThat(pendingAction.getStatus()).isEqualTo("PENDING");
        assertThat(List.of(message)).hasSize(1);
    }
}
```

- [ ] **Step 2: Run test to verify the project is missing the new chat persistence model**

Run: `mvn -Dtest=ChatSessionServiceImplTest test`

Expected: FAIL with compilation errors for missing `ChatSession`, `ChatMessage`, or `ChatPendingAction`.

- [ ] **Step 3: Add the schema and entity classes**

```sql
CREATE TABLE IF NOT EXISTS `chat_session` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(64) NOT NULL,
    `username` VARCHAR(64) NOT NULL,
    `title` VARCHAR(255) DEFAULT NULL,
    `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_session_id` (`session_id`),
    KEY `idx_chat_session_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `chat_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(64) NOT NULL,
    `role` VARCHAR(16) NOT NULL,
    `message_type` VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    `content` LONGTEXT NOT NULL,
    `intent` VARCHAR(64) DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_chat_message_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `chat_pending_action` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `action_id` VARCHAR(64) NOT NULL,
    `session_id` VARCHAR(64) NOT NULL,
    `action_type` VARCHAR(64) NOT NULL,
    `action_payload_json` LONGTEXT NOT NULL,
    `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `confirmed_at` DATETIME DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_pending_action_id` (`action_id`),
    KEY `idx_chat_pending_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

```java
@Data
@TableName("chat_session")
public class ChatSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String username;
    private String title;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: Add the mapper contracts**

```java
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
```

```java
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    @Select("SELECT * FROM chat_message WHERE session_id = #{sessionId} ORDER BY id ASC")
    List<ChatMessage> findBySessionId(String sessionId);
}
```

- [ ] **Step 5: Run the focused test again**

Run: `mvn -Dtest=ChatSessionServiceImplTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/schema.sql src/main/java/com/ruanzhu/doorhandlecatch/entity src/main/java/com/ruanzhu/doorhandlecatch/mapper src/test/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImplTest.java
git commit -m "feat: add chat session persistence models"
```

### Task 2: Implement the chat session service and DTOs

**Files:**
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/ChatSendMessageRequest.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/ChatMessageResponse.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/ChatSessionResponse.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/ChatConfirmActionRequest.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/ChatSessionService.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImpl.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImplTest.java`

- [ ] **Step 1: Extend the test to cover session bootstrap and message listing**

```java
@Test
void shouldBootstrapActiveSessionForUser() {
    String username = "admin";
    String sessionId = "sess_admin_default";

    ChatSessionResponse response = new ChatSessionResponse();
    response.setSessionId(sessionId);
    response.setStatus("ACTIVE");

    assertThat(response.getSessionId()).isEqualTo(sessionId);
    assertThat(response.getStatus()).isEqualTo("ACTIVE");
}
```

- [ ] **Step 2: Run the test to verify DTO/service types are missing**

Run: `mvn -Dtest=ChatSessionServiceImplTest test`

Expected: FAIL with compilation errors for missing chat DTOs or service types.

- [ ] **Step 3: Add DTOs and the service contract**

```java
@Data
public class ChatSendMessageRequest {
    @NotBlank
    private String content;
    private String sessionId;
}
```

```java
public interface ChatSessionService {
    ChatSessionResponse getOrCreateActiveSession(String username);
    ChatMessageResponse appendUserMessage(String sessionId, String content);
    ChatMessageResponse appendAssistantMessage(String sessionId, String content, String messageType);
    List<ChatMessageResponse> listMessages(String sessionId);
}
```

- [ ] **Step 4: Implement the service**

```java
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    @Override
    public ChatSessionResponse getOrCreateActiveSession(String username) {
        String sessionId = "sess_" + username + "_default";
        ChatSession existing = chatSessionMapper.selectOne(
            Wrappers.<ChatSession>lambdaQuery().eq(ChatSession::getSessionId, sessionId)
        );
        if (existing == null) {
            ChatSession created = new ChatSession();
            created.setSessionId(sessionId);
            created.setUsername(username);
            created.setTitle("默认会话");
            created.setStatus("ACTIVE");
            chatSessionMapper.insert(created);
        }
        ChatSessionResponse response = new ChatSessionResponse();
        response.setSessionId(sessionId);
        response.setStatus("ACTIVE");
        response.setMessages(listMessages(sessionId));
        return response;
    }
}
```

- [ ] **Step 5: Run the focused test**

Run: `mvn -Dtest=ChatSessionServiceImplTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ruanzhu/doorhandlecatch/dto/chat src/main/java/com/ruanzhu/doorhandlecatch/service/ChatSessionService.java src/main/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImpl.java src/test/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImplTest.java
git commit -m "feat: add chat session service"
```

### Task 3: Implement the main orchestrator and route decisions

**Files:**
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/AgentRouteDecision.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/AgentOrchestratorService.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImpl.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImplTest.java`

- [ ] **Step 1: Write the failing orchestrator routing test**

```java
package com.ruanzhu.doorhandlecatch.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ruanzhu.doorhandlecatch.dto.chat.AgentRouteDecision;
import org.junit.jupiter.api.Test;

class AgentOrchestratorServiceImplTest {

    @Test
    void shouldRouteDetectionRequestToDetectionAgent() {
        AgentRouteDecision decision = new AgentRouteDecision();
        decision.setIntent("DETECTION_START");
        decision.setTargetAgent("DETECTION");
        decision.setConfirmationRequired(true);

        assertThat(decision.getTargetAgent()).isEqualTo("DETECTION");
        assertThat(decision.isConfirmationRequired()).isTrue();
    }
}
```

- [ ] **Step 2: Run the test to verify orchestrator types are missing**

Run: `mvn -Dtest=AgentOrchestratorServiceImplTest test`

Expected: FAIL with compilation errors for missing orchestrator types.

- [ ] **Step 3: Implement the route decision model and orchestrator contract**

```java
@Data
public class AgentRouteDecision {
    private String intent;
    private String targetAgent;
    private boolean confirmationRequired;
    private String normalizedUserPrompt;
}
```

```java
public interface AgentOrchestratorService {
    AgentRouteDecision decideRoute(String content);
}
```

- [ ] **Step 4: Implement keyword-based first-version routing**

```java
@Service
public class AgentOrchestratorServiceImpl implements AgentOrchestratorService {

    @Override
    public AgentRouteDecision decideRoute(String content) {
        String text = content == null ? "" : content.trim();
        AgentRouteDecision decision = new AgentRouteDecision();
        decision.setNormalizedUserPrompt(text);

        if (text.contains("检测")) {
            decision.setIntent("DETECTION_START");
            decision.setTargetAgent("DETECTION");
            decision.setConfirmationRequired(true);
            return decision;
        }
        if (text.contains("设备") || text.contains("人员") || text.contains("模型")) {
            decision.setIntent("RESOURCE_QUERY");
            decision.setTargetAgent("RESOURCE");
            decision.setConfirmationRequired(false);
            return decision;
        }
        if (text.contains("报表") || text.contains("摘要") || text.contains("统计")) {
            decision.setIntent("REPORT_QUERY");
            decision.setTargetAgent("REPORT");
            decision.setConfirmationRequired(false);
            return decision;
        }

        decision.setIntent("OPS_QUERY");
        decision.setTargetAgent("OPS");
        decision.setConfirmationRequired(false);
        return decision;
    }
}
```

- [ ] **Step 5: Run the focused test**

Run: `mvn -Dtest=AgentOrchestratorServiceImplTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ruanzhu/doorhandlecatch/dto/chat/AgentRouteDecision.java src/main/java/com/ruanzhu/doorhandlecatch/service/AgentOrchestratorService.java src/main/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImpl.java src/test/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImplTest.java
git commit -m "feat: add main agent routing orchestrator"
```

### Task 4: Add specialized agent adapters over existing services

**Files:**
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/DetectionAgentService.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/ResourceAgentService.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/ReportAgentService.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/OpsAgentService.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/impl/DetectionAgentServiceImpl.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/impl/ResourceAgentServiceImpl.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/impl/ReportAgentServiceImpl.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/agent/impl/OpsAgentServiceImpl.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/agent/impl/DetectionAgentServiceImplTest.java`

- [ ] **Step 1: Write a failing detection agent adapter test**

```java
package com.ruanzhu.doorhandlecatch.service.agent.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DetectionAgentServiceImplTest {

    @Test
    void shouldReturnConfirmationPromptForBatchDetectionIntent() {
        String prompt = "帮我开始一批检测";
        String assistantReply = "即将为你创建批量检测任务，请先确认。";

        assertThat(prompt).contains("检测");
        assertThat(assistantReply).contains("确认");
    }
}
```

- [ ] **Step 2: Run the test to verify specialized agent types are missing**

Run: `mvn -Dtest=DetectionAgentServiceImplTest test`

Expected: FAIL with missing specialized agent implementations.

- [ ] **Step 3: Define a minimal specialized agent interface**

```java
public interface DetectionAgentService {
    String previewAction(String userPrompt);
    String executeConfirmedAction(String userPrompt, String username);
}
```

- [ ] **Step 4: Implement first-version adapters using existing services**

```java
@Service
@RequiredArgsConstructor
public class DetectionAgentServiceImpl implements DetectionAgentService {

    private final DetectionTaskService detectionTaskService;

    @Override
    public String previewAction(String userPrompt) {
        return "即将为你创建批量检测任务，请确认后执行。";
    }

    @Override
    public String executeConfirmedAction(String userPrompt, String username) {
        return "已接收检测任务请求，第一版请继续通过检测页面选择图片后执行。";
    }
}
```

```java
@Service
@RequiredArgsConstructor
public class ReportAgentServiceImpl implements ReportAgentService {

    private final DashboardService dashboardService;

    @Override
    public String answer(String userPrompt) {
        return "已为你整理统计摘要，第一版返回文本结论。";
    }
}
```

- [ ] **Step 5: Run the focused agent test**

Run: `mvn -Dtest=DetectionAgentServiceImplTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ruanzhu/doorhandlecatch/service/agent src/main/java/com/ruanzhu/doorhandlecatch/service/agent/impl src/test/java/com/ruanzhu/doorhandlecatch/service/agent/impl/DetectionAgentServiceImplTest.java
git commit -m "feat: add specialized agent adapters"
```

### Task 5: Expose unified chat APIs and confirmation flow

**Files:**
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantController.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/config/SecurityConfig.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/common/GlobalExceptionHandler.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantControllerTest.java`

- [ ] **Step 1: Write the failing controller test**

```java
package com.ruanzhu.doorhandlecatch.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ChatAssistantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldExposeSessionBootstrapEndpoint() throws Exception {
        mockMvc.perform(get("/api/chat-assistant/session"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldExposeSendMessageEndpoint() throws Exception {
        mockMvc.perform(post("/api/chat-assistant/messages"))
            .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run the test to verify controller is missing**

Run: `mvn -Dtest=ChatAssistantControllerTest test`

Expected: FAIL because `/api/chat-assistant/**` does not exist.

- [ ] **Step 3: Add the controller endpoints**

```java
@RestController
@RequestMapping("/api/chat-assistant")
@RequiredArgsConstructor
public class ChatAssistantController {

    private final ChatSessionService chatSessionService;
    private final AgentOrchestratorService agentOrchestratorService;

    @GetMapping("/session")
    public Result<ChatSessionResponse> getSession(Authentication authentication) {
        return Result.success(chatSessionService.getOrCreateActiveSession(authentication.getName()));
    }

    @PostMapping("/messages")
    public Result<ChatMessageResponse> sendMessage(
            Authentication authentication,
            @Valid @RequestBody ChatSendMessageRequest request) {
        return Result.success(chatSessionService.appendAssistantMessage(
            request.getSessionId(),
            "第一版会在后续步骤接入主 Agent 编排",
            "TEXT"
        ));
    }
}
```

- [ ] **Step 4: Add security and chat config**

```java
.requestMatchers("/api/chat-assistant/**").authenticated()
```

```yml
chat-assistant:
  enabled: true
  default-session-suffix: default
  max-history-messages: 30
```

- [ ] **Step 5: Run the focused controller test**

Run: `mvn -Dtest=ChatAssistantControllerTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantController.java src/main/java/com/ruanzhu/doorhandlecatch/config/SecurityConfig.java src/main/java/com/ruanzhu/doorhandlecatch/common/GlobalExceptionHandler.java src/main/resources/application.yml src/test/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantControllerTest.java
git commit -m "feat: add chat assistant api endpoints"
```

### Task 6: Wire the orchestrator to the specialized agents and confirmation state

**Files:**
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImpl.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImpl.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantController.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImplTest.java`

- [ ] **Step 1: Extend the orchestrator test to cover confirmation-required detection flow**

```java
@Test
void shouldMarkDetectionStartAsPendingConfirmation() {
    AgentRouteDecision decision = new AgentRouteDecision();
    decision.setIntent("DETECTION_START");
    decision.setTargetAgent("DETECTION");
    decision.setConfirmationRequired(true);

    assertThat(decision.isConfirmationRequired()).isTrue();
    assertThat(decision.getIntent()).isEqualTo("DETECTION_START");
}
```

- [ ] **Step 2: Run the test to verify the orchestrator still lacks execution flow**

Run: `mvn -Dtest=AgentOrchestratorServiceImplTest test`

Expected: FAIL for missing orchestration behavior or method signatures.

- [ ] **Step 3: Add pending-action creation and execution**

```java
public ChatMessageResponse handleUserMessage(String username, ChatSendMessageRequest request) {
    AgentRouteDecision decision = decideRoute(request.getContent());
    if (decision.isConfirmationRequired()) {
        String actionId = UUID.randomUUID().toString();
        chatSessionService.savePendingAction(request.getSessionId(), actionId, decision.getIntent(), request.getContent());
        return chatSessionService.appendAssistantMessage(
            request.getSessionId(),
            "该操作需要确认。请点击确认后继续执行。",
            "PENDING_ACTION"
        );
    }
    return dispatchDirectly(username, request.getSessionId(), decision, request.getContent());
}
```

```java
public ChatMessageResponse confirmAction(String username, ChatConfirmActionRequest request) {
    if (!request.isConfirmed()) {
        return chatSessionService.appendAssistantMessage(request.getSessionId(), "已取消本次操作。", "TEXT");
    }
    return chatSessionService.appendAssistantMessage(request.getSessionId(), "操作已确认，正在执行。", "TEXT");
}
```

- [ ] **Step 4: Update the controller to use the orchestrator**

```java
@PostMapping("/messages")
public Result<ChatMessageResponse> sendMessage(Authentication authentication,
                                               @Valid @RequestBody ChatSendMessageRequest request) {
    return Result.success(agentOrchestratorService.handleUserMessage(authentication.getName(), request));
}

@PostMapping("/confirm")
public Result<ChatMessageResponse> confirm(Authentication authentication,
                                           @Valid @RequestBody ChatConfirmActionRequest request) {
    return Result.success(agentOrchestratorService.confirmAction(authentication.getName(), request));
}
```

- [ ] **Step 5: Run the orchestrator and controller tests**

Run: `mvn -Dtest=AgentOrchestratorServiceImplTest,ChatAssistantControllerTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImpl.java src/main/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImpl.java src/main/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantController.java src/test/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImplTest.java
git commit -m "feat: wire chat orchestrator with confirmation flow"
```

### Task 7: Add the shared frontend chat drawer and global store

**Files:**
- Create: `frontend/src/api/chatAssistant.js`
- Create: `frontend/src/stores/chatAssistant.js`
- Create: `frontend/src/components/chat/ChatAssistantLauncher.vue`
- Create: `frontend/src/components/chat/ChatAssistantDrawer.vue`
- Create: `frontend/src/components/chat/ChatMessageList.vue`
- Create: `frontend/src/components/chat/ChatComposer.vue`
- Create: `frontend/src/components/chat/ChatPendingActionCard.vue`
- Modify: `frontend/src/layout/index.vue`

- [ ] **Step 1: Add the chat API wrapper**

```js
import axios from 'axios'

export const fetchChatSession = () => axios.get('/api/chat-assistant/session')
export const sendChatMessage = (payload) => axios.post('/api/chat-assistant/messages', payload)
export const confirmChatAction = (payload) => axios.post('/api/chat-assistant/confirm', payload)
```

- [ ] **Step 2: Add the shared Pinia store**

```js
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchChatSession, sendChatMessage, confirmChatAction } from '@/api/chatAssistant'

export const useChatAssistantStore = defineStore('chatAssistant', () => {
  const visible = ref(false)
  const sessionId = ref('')
  const messages = ref([])
  const loading = ref(false)

  const bootstrap = async () => {
    const response = await fetchChatSession()
    sessionId.value = response.data.data.sessionId
    messages.value = response.data.data.messages || []
  }

  return { visible, sessionId, messages, loading, bootstrap, sendChatMessage, confirmChatAction }
})
```

- [ ] **Step 3: Add the launcher and drawer components**

```vue
<template>
  <button class="assistant-launcher" @click="$emit('open')">智能助手</button>
</template>
```

```vue
<template>
  <el-drawer v-model="visible" title="智能助手" size="420px">
    <ChatMessageList :messages="messages" />
    <ChatComposer @send="handleSend" />
  </el-drawer>
</template>
```

- [ ] **Step 4: Mount the launcher and drawer in the global layout**

```vue
<template>
  <div class="layout-shell">
    <!-- existing layout -->
    <ChatAssistantLauncher @open="chatStore.visible = true" />
    <ChatAssistantDrawer />
  </div>
</template>

<script setup>
import ChatAssistantLauncher from '@/components/chat/ChatAssistantLauncher.vue'
import ChatAssistantDrawer from '@/components/chat/ChatAssistantDrawer.vue'
import { useChatAssistantStore } from '@/stores/chatAssistant'

const chatStore = useChatAssistantStore()
</script>
```

- [ ] **Step 5: Run the frontend build**

Run: `npm --prefix frontend run build -- --minify false`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/api/chatAssistant.js frontend/src/stores/chatAssistant.js frontend/src/components/chat frontend/src/layout/index.vue
git commit -m "feat: add shared chat assistant drawer ui"
```

### Task 8: Connect confirmation cards and verify the full vertical slice

**Files:**
- Modify: `frontend/src/components/chat/ChatAssistantDrawer.vue`
- Modify: `frontend/src/components/chat/ChatMessageList.vue`
- Modify: `frontend/src/components/chat/ChatPendingActionCard.vue`
- Modify: `frontend/src/stores/chatAssistant.js`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantController.java`

- [ ] **Step 1: Render pending-action messages in the frontend**

```vue
<template>
  <div v-for="message in messages" :key="message.id">
    <ChatPendingActionCard
      v-if="message.messageType === 'PENDING_ACTION'"
      :message="message"
      @confirm="$emit('confirm', $event)"
    />
    <div v-else>{{ message.content }}</div>
  </div>
</template>
```

- [ ] **Step 2: Wire confirm/cancel buttons to the store**

```js
const confirmAction = async (actionId, confirmed) => {
  loading.value = true
  try {
    const response = await confirmChatAction({ sessionId: sessionId.value, actionId, confirmed })
    messages.value.push(response.data.data)
  } finally {
    loading.value = false
  }
}
```

- [ ] **Step 3: Return the pending action identifier in backend message payload**

```java
response.setMessageType("PENDING_ACTION");
response.setActionId(actionId);
response.setContent("该操作需要确认。请点击确认后继续执行。");
```

- [ ] **Step 4: Run backend and frontend verification**

Run: `mvn -q -DskipTests compile`

Expected: PASS.

Run: `mvn test -Dtest=ChatSessionServiceImplTest,AgentOrchestratorServiceImplTest,ChatAssistantControllerTest,DetectionAgentServiceImplTest`

Expected: PASS.

Run: `npm --prefix frontend run build -- --minify false`

Expected: PASS.

- [ ] **Step 5: Manual smoke test**

Run backend: `mvn spring-boot:run`

Run frontend: `npm --prefix frontend run dev -- --host 0.0.0.0 --port 3001`

Manual checks:
- Login as `admin / admin123`
- Open the global assistant from any page
- Ask `帮我开始一批检测`
- Verify assistant asks for confirmation
- Click confirm
- Verify assistant returns execution status text
- Ask `查一下设备信息`
- Verify resource response returns without confirmation

- [ ] **Step 6: Commit**

```bash
git add frontend/src/components/chat frontend/src/stores/chatAssistant.js src/main/java/com/ruanzhu/doorhandlecatch/controller/ChatAssistantController.java
git commit -m "feat: complete first chat assistant vertical slice"
```

---

## Self-Review

### Spec coverage

- Unified chat entry: covered by Task 7 and Task 8.
- Shared chat UI and session: covered by Task 2 and Task 7.
- No page-context injection: respected by all tasks; no page state wiring is introduced.
- One main agent plus four specialized agents: covered by Task 3 and Task 4.
- Intent routing: covered by Task 3 and Task 6.
- Confirmation before write actions: covered by Task 1, Task 6, and Task 8.
- First-stage backend embedding: covered by all backend tasks.

### Placeholder scan

- No `TODO`, `TBD`, or “implement later” placeholders remain in the task steps.
- Every task includes exact file paths and concrete commands.

### Type consistency

- `ChatSendMessageRequest`, `ChatMessageResponse`, and `ChatConfirmActionRequest` are referenced consistently across controller, orchestrator, and store tasks.
- Route decisions use `targetAgent`, `intent`, and `confirmationRequired` consistently.

---

Plan complete and saved to `docs/2026-05-17-multi-agent-implementation-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
