# Hermes 多 Agent 编排与分层记忆代码实现详解

## 一、亮点简历写法

自主设计 Hermes 多 Agent 协作框架，基于 StateGraph 编排 Router、Detection、Resource、Report、Ops 等专业 Agent，通过条件路由、槽位补全、人工确认、MySQL Checkpoint 恢复和运行守卫实现复杂业务流程；融合会话消息、短期状态、Mem0 长期记忆与 ChromaDB RAG，提升多轮对话连续性和回答可靠性。

## 二、整体架构：状态图编排 + 四层记忆

```text
用户消息
   │
   ▼
AgentOrchestratorServiceImpl
   ├─ MySQL Checkpoint ── 恢复 slots/messages/phase
   ├─ ChromaDB RAG ────── 检索系统手册
   └─ Mem0 ────────────── 检索用户长期记忆
   │
   ▼
┌──────────────── Hermes StateGraph ────────────────┐
│ Router                                             │
│   ├─ 参数不足 → SlotFilling → Responder（追问）    │
│   ├─ 查询 → Detection/Resource/Report/Ops Agent   │
│   └─ 操作 → HumanConfirm → 暂停并保存 Checkpoint  │
│                                  │ 用户确认         │
│                                  └→ resume()       │
│ 专业 Agent → Responder → END                       │
│ 任意异常/循环 → Fallback                           │
└────────────────────────────────────────────────────┘
   │
   ├─ 保存助手消息与最新 Checkpoint
   └─ 异步写入 Mem0
```

## 三、代码位置索引

| 模块 | 文件 | 关键职责 |
| --- | --- | --- |
| Agent 总编排 | `AgentOrchestratorServiceImpl.java` | 上下文、记忆、图执行、SSE |
| 图装配 | `StateGraphConfiguration.java` | 节点、边、优先级和重试 |
| 图运行时 | `CompiledGraph.java` | invoke、resume、checkpoint、guard |
| 状态对象 | `AgentState.java` | 跨节点共享数据 |
| 路由节点 | `RouterNode.java` | 意图和目标 Agent 决策 |
| 槽位节点 | `SlotFillingNode.java` | 参数抽取和缺失参数追问 |
| 人工确认 | `HumanConfirmNode.java` | 高风险动作暂停点 |
| 短期状态 | `MySqlCheckpointer.java` | State JSON 持久化 |
| 长期记忆 | `Mem0Client.java` | 用户记忆检索和异步写入 |
| 知识检索 | `RagKnowledgeService.java` | 查询改写、向量召回、重排 |

源码目录：`src/main/java/com/ruanzhu/doorhandlecatch/`。

## 四、详细流程图

### 4.1 普通查询完整流程

```text
Step 1  创建/校验 session，保存用户消息
   ↓
Step 2  checkpointer.load(sessionId)
        恢复 recent_messages、slots、phase、route_decision
   ↓
Step 3  RAG 检索系统知识 + Mem0 检索用户记忆
   ↓
Step 4  RouterNode 判断 intent=DETECTION_QUERY
        targetAgent=DETECTION
   ↓
Step 5  DetectionAgentNode 调用 DetectionAgentService
   ↓
Step 6  ResponderNode 生成 TEXT 或 BUSINESS_CARD
   ↓
Step 7  每个节点后保存 Checkpoint；结束后保存助手消息
   ↓
Step 8  Mem0Client.addMemoryAsync() 异步提取长期记忆
```

### 4.2 参数不足流程

```text
用户：查询检测任务
   ↓
Router：识别任务查询，但缺 taskId
   ↓
conversation_phase=COLLECTING
missing_slots=[taskId]
   ↓
SlotFilling：生成“请提供任务编号”
   ↓
下一轮用户：det_20260704_001
   ↓
恢复 Checkpoint → 合并 taskId → 路由 DetectionAgent
```

### 4.3 高风险动作确认流程

```text
用户：将 det_001 标记为返工
   ↓
Router：DETECTION_ACTION
   ↓
HumanConfirmNode：生成 actionId，exit=PENDING_CONFIRMATION
   ↓
保存 StateGraph Checkpoint + chat_pending_action(PENDING)
   ↓
用户点击确认
   ↓
PENDING --条件更新--> EXECUTING
   ↓
chatGraph.resume(sessionId, confirmed=true)
   ↓
DetectionAgent 执行真实 Service
   ↓
EXECUTING → COMPLETED（异常则 FAILED）
```

## 五、关键机制详解

### 5.1 四层记忆为什么不能互相替代

| 层级 | 保存内容 | 生命周期 | 用途 |
| --- | --- | --- | --- |
| `chat_message` | 用户可见对话 | 长期 | 展示历史消息 |
| Checkpoint | 当前节点、槽位、中间结果 | 会话级 | 恢复工作流 |
| Mem0 | 用户偏好、历史事实 | 跨会话 | 个性化回答 |
| RAG | 手册、规则、系统知识 | 全局 | 降低知识幻觉 |

### 5.2 图运行守卫

系统限制最大迭代次数、执行时间、单节点访问次数、重复路由次数和 Trace 长度。触发守卫后写入 `guard_reason`，转入 Fallback，而不是继续循环消耗线程和 Token。

## 六、代码详解

### 6.1 StateGraph 节点与条件边

文件：`stategraph/config/StateGraphConfiguration.java`

```java
return new StateGraph()
    .addNode("router", routerNode)
    .addNode("detection", detectionNode)
    .addNode("resource", resourceNode)
    .addNode("report", reportNode)
    .addNode("ops", opsNode)
    .addNode("confirm", humanConfirmNode)
    .addNode("responder", responderNode)
    .setEntryPoint("router")
    .setFallbackNode("fallback")
    .setMaxIterations(15)
    .setNodeRetry("router", 2)
    .addConditionalEdge("router",
        s -> isIntent(s, "DETECTION_QUERY"), "detection")
    .addConditionalEdge("router",
        s -> isIntent(s, "DETECTION_ACTION"), "confirm")
    .compile(checkpointer);
```

逐句解释：

1. `addNode` 注册节点名与实现对象，节点之间只通过 `AgentState` 传递数据。
2. 所有新请求从 `router` 开始；未命中或异常时进入 `fallback`。
3. `maxIterations` 是第一层防死循环保护。
4. Router 允许重试两次，缓解大模型临时输出格式异常。
5. 查询直接进入专业 Agent；写操作先进入确认节点。
6. `compile(checkpointer)` 将图结构冻结，并接入 MySQL 持久化。

### 6.2 从 Checkpoint 恢复上下文

文件：`service/impl/AgentOrchestratorServiceImpl.java`

```java
AgentState state = AgentState.create(sessionId, request.getContent(), username);
AgentState previous = checkpointer.load(sessionId);
if (previous != null) {
    for (String key : CONTEXT_KEYS) {
        Object value = previous.get(key, Object.class);
        if (value != null) state.set(key, value);
    }
}
```

逐句解释：

1. 每轮请求创建新 State，确保本轮输入和用户身份是最新的。
2. 通过 `sessionId` 读取上一轮快照。
3. 只复制白名单 `CONTEXT_KEYS`，避免把旧错误和临时流消费者带入新请求。
4. 恢复的槽位和阶段使“下一句话只补参数”的多轮交互成为可能。

### 6.3 注入 RAG 与用户记忆

```java
String ragContext = ragKnowledgeService.retrieveContext(request.getContent());
if (StringUtils.hasText(ragContext)) {
    state.set(AgentState.KEY_RAG_CONTEXT, ragContext);
}
List<Map<String, Object>> memories =
        mem0Client.searchMemories(username, request.getContent(), 5);
state.set("user_memories", memories);
state.set("user_memory_context", mem0Client.formatMemoriesAsContext(memories));
```

逐句解释：

1. RAG 以当前问题检索公共知识，并注入专用字段。
2. Mem0 按 `username` 隔离用户，以当前问题做相关性搜索，最多返回 5 条。
3. 原始列表便于结构化处理，格式化文本便于拼接到模型上下文。
4. 记忆服务失败时主对话仍可继续，属于增强能力而非强依赖。

### 6.4 确认动作的并发抢占

```java
boolean claimed = chatSessionService.transitionPendingAction(
    sessionId, actionId, "PENDING", "EXECUTING", null);
if (!claimed) {
    throw new BusinessException("待确认动作正在处理或已处理，请勿重复提交");
}
AgentState result = chatGraph.resume(sessionId,
        Map.of(AgentState.KEY_CONFIRMED, true));
```

逐句解释：

1. 数据库条件更新只有在当前状态仍为 `PENDING` 时才成功。
2. 两个并发确认请求中只有一个能得到 `claimed=true`。
3. 抢占成功后才从 Checkpoint 恢复图，避免业务动作被执行两次。

## 七、关键设计总结

| 特性 | 实现方式 | 代码位置 |
| --- | --- | --- |
| 多 Agent 路由 | Router + 条件边 | `StateGraphConfiguration` |
| 多轮槽位 | Checkpoint 恢复 `slots/phase` | `AgentOrchestratorServiceImpl` |
| 长期记忆 | Mem0 按用户检索与异步写入 | `Mem0Client` |
| 知识增强 | RAG 查询改写、召回、重排 | `RagKnowledgeService` |
| 人工确认 | 条件状态抢占 + `resume()` | `confirmAction` |
| 防死循环 | 次数、时间、访问和路由守卫 | `CompiledGraph` |

## 八、技术取舍与优化

- 当前限流窗口保存在单 JVM 内；双实例下可升级为 Redis 分布式限流。
- Checkpoint 使用 JSON，扩展方便但缺少强 Schema；可增加版本迁移器。
- Mem0 和 RAG 故障采用降级策略，保证核心业务可用。
- “Hermes Agent”是项目架构命名，源码仍使用通用 `stategraph` 包名。

## 九、面试问题与答案

### 1. 为什么不用单 Agent？

不同业务的数据源、参数和权限规则不同。专业 Agent 能缩短提示词、隔离职责，并可独立测试；Router 负责把复杂度集中在路由层。

### 2. Checkpoint 和消息历史有什么区别？

消息历史是用户可见文本，Checkpoint 是机器状态，包括节点、槽位、路由、中间结果和暂停原因。恢复人工确认必须依赖 Checkpoint。

### 3. Mem0 与 RAG 有什么区别？

Mem0 保存用户私有的长期事实和偏好；RAG 保存全局手册和规则。两者分别解决个性化和知识准确性。

### 4. 如何防止 Agent 无限循环？

除最大迭代次数外，还检查执行耗时、节点访问次数和重复路由次数，触发后写入原因并进入 Fallback。

### 5. 用户重复点击确认怎么办？

通过 `WHERE status='PENDING'` 的条件更新抢占执行权，只有一次请求能恢复图并执行业务。

### 6. 多实例下 Checkpoint 为什么仍能恢复？

Checkpoint 存在 MySQL，不在 JVM 内存中；后续请求即使被 Nginx 分配到另一实例，也能按 `sessionId` 加载状态。
