# 智能对话助手升级传统业务系统代码实现详解

## 一、亮点简历写法

将传统“菜单检索—页面跳转—多表单填写”升级为自然语言业务入口，通过 Hermes Agent 打通检测任务、设备、员工、模型、质量复核、报告和运维诊断；支持语音输入、SSE 流式响应、结构化业务卡片与高风险操作二次确认，使助手从知识问答升级为可执行真实业务的智能操作层。

## 二、整体架构：对话入口 + 真实业务服务

```text
ChatAssistantDrawer / Voice
          │
          ▼
Pinia chatAssistant Store
          │ POST /messages/stream
          ▼
ChatAssistantController ── SpeechTranscriptionService
          │
          ▼
AgentOrchestratorService → Hermes StateGraph
          │
   ┌──────┼──────────┬───────────┐
   ▼      ▼          ▼           ▼
检测服务  资源服务    报表服务     运维服务
   │
   ▼
MySQL / Redis / Kafka / OSS
   │
   ▼ SSE: status/chunk/done
文本消息 / 业务卡片 / 待确认卡片
```

## 三、代码位置索引

| 模块 | 文件 | 作用 |
| --- | --- | --- |
| 全局入口 | `frontend/src/layout/index.vue` | 挂载助手按钮和抽屉 |
| 对话界面 | `ChatAssistantDrawer.vue` | 消息、会话、诊断展示 |
| 输入组件 | `ChatComposer.vue` | 文本、快捷指令、语音 |
| 前端状态 | `stores/chatAssistant.js` | 会话、消息、SSE 事件 |
| API | `api/chatAssistant.js` | 对话和确认接口 |
| 后端入口 | `ChatAssistantController.java` | REST、SSE、语音接口 |
| 编排服务 | `AgentOrchestratorServiceImpl.java` | Agent 主流程 |
| 会话服务 | `ChatSessionServiceImpl.java` | 会话、消息、所有权 |
| 动作 Mapper | `ChatPendingActionMapper.java` | 原子状态迁移 |

## 四、详细流程图

### 4.1 查询业务流程

```text
Step 1  用户输入“查询 det_001 的检测结果”
   ↓
Step 2  Store 发送当前 sessionId、页面路由和页面标题
   ↓
Step 3  Controller 从 Authentication 获取 username
   ↓
Step 4  编排器验证会话归属并保存用户消息
   ↓
Step 5  Hermes 路由 DetectionAgent，调用真实 Service
   ↓
Step 6  返回 TEXT 或结构化 BUSINESS_CARD
   ↓
Step 7  SSE 分别推送 status、chunk、done
   ↓
Step 8  Store 合并临时消息，组件渲染卡片和跳转按钮
```

### 4.2 修改业务流程

```text
用户：将 det_001 处置为返工
   ↓
后端返回 messageType=PENDING_ACTION、actionId=act_001
   ↓
前端显示确认/取消按钮
   ├─ 取消 → POST /confirm confirmed=false → CANCELLED
   └─ 确认 → POST /confirm confirmed=true
                         ↓
                  PENDING → EXECUTING
                         ↓
                  执行 Detection Service
                         ↓
                  COMPLETED / FAILED
```

### 4.3 SSE 生命周期

```text
connected → status(会话已准备)
          → status(上下文已恢复)
          → status(正在检索知识与记忆)
          → chunk("任务 det_")
          → chunk("001 已完成")
          → done(messageResponse)
```

## 五、关键机制详解

### 5.1 为什么是“业务助手”而不是聊天机器人

大模型只负责意图和参数理解，数据读取与修改仍通过 Java Service。它不会生成 SQL 后直接执行，因此权限、状态迁移、事务和参数校验仍由传统业务层控制。

### 5.2 结构化业务卡片

`ChatMessageResponse` 同时返回内容、消息类型、意图和动作 ID。前端按类型渲染任务、质量队列、批次追溯、Agent 健康或确认卡片，避免把复杂业务对象压成一段文本。

### 5.3 用户隔离

后端以认证用户名校验会话归属。仅知道其他人的 `sessionId` 仍无法读取其消息或恢复其 Checkpoint。

## 六、代码详解

### 6.1 SSE 控制器入口

文件：`controller/ChatAssistantController.java`

```java
@PostMapping(value = "/messages/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter streamMessage(Authentication authentication,
        @Valid @RequestBody ChatSendMessageRequest request) {
    return agentOrchestratorService.streamUserMessage(
            authentication.getName(), request);
}
```

逐句解释：

1. `TEXT_EVENT_STREAM_VALUE` 告诉客户端这是持续事件流。
2. 用户名只从 Spring Security 上下文取得，不信任请求体中的用户字段。
3. DTO 先通过 Bean Validation 校验，再进入编排服务。
4. Controller 不承担 Agent 逻辑，保持接口层简单。

### 6.2 后端流式事件

```java
SseEmitter emitter = new SseEmitter(300_000L);
sendStreamEvent(emitter, "connected",
        ChatStreamEvent.status(sessionId, "已连接智能助手流式通道"));
sendStreamEvent(emitter, "chunk", ChatStreamEvent.builder()
        .type("chunk").sessionId(sessionId).content(token).build());
sendStreamEvent(emitter, "done", ChatStreamEvent.builder()
        .type("done").messageResponse(response).build());
emitter.complete();
```

逐句解释：

1. 300 秒覆盖知识检索、Agent 执行和模型生成时间。
2. `connected` 让前端立即确认链路建立。
3. `chunk` 只承载增量文本，前端可以连续拼接。
4. `done` 携带权威的完整消息和业务字段，用于最终落盘展示。
5. 正常或异常都主动结束 emitter，避免线程和连接泄漏。

### 6.3 会话所有权校验

文件：`service/impl/ChatSessionServiceImpl.java`

```java
ChatSession session = chatSessionMapper.selectOne(
    new LambdaQueryWrapper<ChatSession>()
        .eq(ChatSession::getSessionId, sessionId)
        .eq(ChatSession::getUsername, username)
        .last("limit 1"));
if (session == null) {
    throw new BusinessException(404, "会话不存在");
}
```

逐句解释：

1. 查询条件同时包含会话 ID 和当前用户。
2. 对越权请求统一返回“会话不存在”，减少信息泄露。
3. 所有读取、重命名、归档、删除和 Checkpoint 查询都复用该校验。

### 6.4 待确认动作原子迁移

文件：`mapper/ChatPendingActionMapper.java`

```sql
UPDATE chat_pending_action
SET status = #{targetStatus},
    error_message = #{errorMessage},
    confirmed_at = CURRENT_TIMESTAMP
WHERE session_id = #{sessionId}
  AND action_id = #{actionId}
  AND status = #{expectedStatus}
```

逐句解释：

1. `session_id + action_id` 精确定位动作。
2. `status = expectedStatus` 实现乐观并发控制。
3. 返回影响行数为 1 才代表抢占成功。
4. 重复请求因状态已变化而更新 0 行，不会再次执行业务。

## 七、接口汇总

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/chat-assistant/messages` | 普通消息 |
| POST | `/api/chat-assistant/messages/stream` | SSE 流式消息 |
| POST | `/api/chat-assistant/confirm` | 确认/取消动作 |
| POST | `/api/chat-assistant/voice/transcribe` | 语音转文字 |
| GET | `/api/chat-assistant/sessions` | 会话列表 |
| GET | `/api/chat-assistant/agent-health` | Agent 运行诊断 |

## 八、关键设计总结

| 特性 | 实现方式 | 代码位置 |
| --- | --- | --- |
| 自然语言业务入口 | Agent 调用真实 Service | `AgentOrchestratorServiceImpl` |
| 流式反馈 | `SseEmitter` 多事件协议 | `ChatAssistantController` |
| 业务卡片 | messageType + payload | `ChatBusinessCard.vue` |
| 操作确认 | pending action 状态机 | `ChatPendingActionMapper` |
| 会话隔离 | sessionId + username | `ChatSessionServiceImpl` |
| 语音入口 | Multipart + ASR 服务 | `SpeechTranscriptionServiceImpl` |

## 九、技术取舍与优化

- SSE 适合服务器单向推送，协议简单；若未来需要实时打断和双向音频，可增加 WebSocket。
- 当前前端通过结构化类型选择组件；类型继续增多时可引入卡片组件注册表。
- 用户会话已隔离，业务表权限还应持续采用统一数据权限策略。
- 大模型不直接执行 SQL，牺牲了一点灵活性，换来审计和安全性。

## 十、面试问题与答案

### 1. 与普通客服机器人有什么区别？

本项目会调用真实业务 Service，并支持查询、卡片展示和确认后执行，不只是输出操作说明。

### 2. 为什么选择 SSE？

对话主要是服务器向客户端持续输出，SSE 基于 HTTP、实现和代理配置更简单，并支持事件类型和自动流式读取。

### 3. 为什么不让模型直接执行 SQL？

模型 SQL 可能越权、误修改或受提示注入影响。项目让模型理解意图，Service 负责固定接口、权限、事务和状态校验。

### 4. 如何保证员工之间会话隔离？

从认证上下文取得用户名，所有会话访问同时按 `sessionId` 和 `username` 查询。

### 5. SSE 断开后怎么办？

消息和 Checkpoint 在服务端持久化，客户端重新连接后可加载会话历史；已开始的动作由状态机判断是否允许重试。

### 6. 双击确认为什么不会执行两次？

确认动作使用带期望状态的数据库条件更新，只有一个请求能完成 `PENDING → EXECUTING`。

