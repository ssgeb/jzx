# 智能体 Python 服务迁移设计

## 1. 背景与目标

项目当前在 `legacy-service` 中使用 Java 自研 `StateGraph` 实现意图路由、槽位补全、专业 Agent、人工确认、结果质量门禁、失败回退和 MySQL Checkpoint。该实现已经具备可靠性保护，但模型编排、提示词、RAG 和长期记忆与 Java 业务代码耦合较深，后续扩展 Agent 节点和调试模型链路的成本较高。

本次改造把“智能体推理与编排”迁移到独立 Python 服务，采用 FastAPI 提供内部 HTTP/SSE 接口、LangGraph 实现状态图；Java 继续作为浏览器的唯一后端入口和业务数据边界。

本次目标：

1. 前端接口、登录方式和使用体验不变。
2. Python 接管意图识别、图编排、专业 Agent、模型调用、RAG 和 Mem0 长期记忆。
3. Java 保留身份认证、多租户校验、会话消息、待确认动作和 Checkpoint 持久化。
4. Python 不能直接修改核心业务数据库，业务查询和写操作必须通过受控的 Java 内部工具接口完成。
5. 支持逐步切换和安全回退，迁移失败时不影响现有 Java 智能体继续工作。

## 2. 范围

### 2.1 本次包含

- 新建 `python-assistant-service`，使用 Python、FastAPI、LangGraph 和 Pydantic。
- 将 Router、Slot Filling、Detection、Resource、Report、Ops、Human Confirm、Quality Gate、Responder、Fallback 的编排语义迁移到 Python。
- Java 新增 Python 智能体客户端和引擎选择配置。
- 定义 Java 与 Python 之间的同步调用、流式调用、恢复执行和健康检查契约。
- 定义 Python 调用 Java 业务能力的受限内部工具接口。
- 保持现有 SSE 事件、人工确认状态机和多租户隔离规则。
- 增加 Python 单元测试、Java 适配器测试、契约测试和端到端回归测试。

### 2.2 本次不包含

- 不实现 Skill 下载、创建、安装或分配。
- 不引入 FastMCP；第一阶段内部工具使用类型明确的 HTTP JSON 接口。
- 不让浏览器直接访问 Python 服务。
- 不迁移 Java 登录鉴权、会话表、消息表、待确认动作表和核心业务表。
- 不让 Python 直接连接核心 MySQL，也不在 Python 中复制 Java 业务 Service 的数据库逻辑。
- 不调整前端聊天接口和页面。
- 不立即删除 Java StateGraph；它在灰度期作为可切换的回退实现保留。
- 不改造现有 Maven `assistant-service` 骨架。本阶段 Python 代码放在独立目录，避免破坏 Maven Reactor；待 Java 智能体彻底下线后再单独清理该骨架。

## 3. 技术职责说明

- **FastAPI**：提供 Java 调用 Python 的 HTTP 和 SSE 服务接口，作用相当于 Python 服务的 Controller 层。
- **LangGraph**：负责主 Agent、子 Agent、节点跳转、状态流转、中断与恢复，是本次替换 Java StateGraph 的核心。
- **Pydantic**：校验 Java/Python 之间的请求、响应和状态字段，拒绝缺字段或类型错误的数据。
- **FastMCP**：是实现 MCP Server/Client 的工具包，适合以后把工具能力发布成标准 MCP 协议；它不是 Agent 编排框架，因此本阶段不需要。

## 4. 总体架构

```text
Vue 前端
   │  原有 /api/chat-assistant/**
   ▼
┌──────────────────────────────────────────────────────┐
│ Java legacy-service                                  │
│                                                      │
│ 登录鉴权 │ 多租户校验 │ 限流 │ 会话/消息 │ Checkpoint │
│ 待确认动作 CAS 状态机 │ SSE 对外代理                 │
└──────────────────────┬───────────────────────────────┘
                       │ 内部签名 HTTP / SSE
                       ▼
┌──────────────────────────────────────────────────────┐
│ Python Assistant Service                             │
│                                                      │
│ FastAPI │ LangGraph │ 模型调用 │ RAG │ Mem0          │
│ Router  │ 槽位补全  │ 专业 Agent │ 质量门禁 │ 回退   │
└──────────────────────┬───────────────────────────────┘
                       │ 受限内部工具 API
                       ▼
┌──────────────────────────────────────────────────────┐
│ Java 业务服务                                         │
│ 检测 │ 设备/人员/模型 │ 报表 │ 运维 │ 写操作幂等校验 │
└──────────────────────────────────────────────────────┘
```

浏览器只信任 Java。Java 从已认证的 `TenantPrincipal` 生成用户上下文，再通过服务间签名交给 Python。Python 不接受浏览器传入的租户身份，也不自行推断用户所属范围。

## 5. 组件边界

### 5.1 Java 保留的职责

Java 是系统事实数据的所有者，保留：

- 对外 `/api/chat-assistant/**` 接口。
- 用户认证、`tenantUserId` 提取、会话归属校验和用户级限流。
- `chat_session`、`chat_message`、`chat_pending_action` 的读写。
- MySQL Checkpoint 的加载和保存。
- 待确认动作 `PENDING → EXECUTING/CANCELLED → COMPLETED/FAILED` 的原子状态迁移。
- Python SSE 到前端 SSE 的事件代理。
- 检测、资源、报表和运维能力的内部工具接口。
- 引擎选择、故障切换、审计日志和指标汇总。

### 5.2 Python 接管的职责

Python 接管：

- 多轮上下文理解和意图分类。
- 槽位提取、补全和任务阶段判断。
- 主 Agent 对专业 Agent 的路由和执行编排。
- DeepSeek 等模型调用、提示词组织和结果解析。
- RAG 检索和 Mem0 长期记忆读写。
- 确定性结果质量检查、执行轨迹、运行守卫和安全回退回答。
- 将可持久化状态返回 Java，由 Java 保存 Checkpoint。

Python 服务本身保持无核心业务状态：单次请求所需的 Checkpoint 由 Java 随请求传入，运行结束后的状态快照随响应返回。

### 5.3 目录建议

```text
python-assistant-service/
├── app/
│   ├── api/              # FastAPI 内部接口
│   ├── graph/            # LangGraph 定义、状态和守卫
│   ├── agents/           # Detection/Resource/Report/Ops
│   ├── clients/          # Java 工具、模型、RAG、Mem0 客户端
│   ├── security/         # 请求签名和重放防护
│   ├── schemas/          # Pydantic 请求/响应模型
│   ├── settings.py
│   └── main.py
├── tests/
├── pyproject.toml
├── requirements.lock
└── Dockerfile
```

## 6. LangGraph 执行设计

### 6.1 节点映射

| 现有 Java 节点 | Python 节点 | 职责 |
| --- | --- | --- |
| `RouterNode` | `router` | 多轮意图识别、关键词纠偏和目标 Agent 路由 |
| `SlotFillingNode` | `slot_filling` | 提取槽位并追问缺失参数 |
| `DetectionAgentNode` | `detection_agent` | 检测任务查询与操作编排 |
| `ResourceAgentNode` | `resource_agent` | 设备、人员、模型查询与操作编排 |
| `ReportAgentNode` | `report_agent` | 报表查询与生成编排 |
| `OpsAgentNode` | `ops_agent` | 导航、系统状态和通用问答 |
| `HumanConfirmNode` | `human_confirm` | 生成待确认动作并中断图执行 |
| `ResultQualityNode` | `quality_gate` | 校验结果字段和错误状态 |
| `ResponderNode` | `responder` | 统一形成最终回复 |
| `FallbackNode` | `fallback` | 发生异常或触发守卫时安全降级 |

### 6.2 执行流程

```text
收到消息
   │
   ▼
┌────────────────────────────────────────────┐
│ Router：识别 NEW_TASK / SUPPLEMENT /       │
│ MODIFY / FOLLOWUP / CHITCHAT，并选择 Agent │
└─────────────────────┬──────────────────────┘
                      │
                 槽位是否完整？
                 ┌────┴────┐
                 │         │
                否         是
                 │         │
                 ▼         ▼
       ┌──────────────┐  是否为写操作？
       │ Slot Filling │     ┌────┴────┐
       │ 生成参数追问 │     │         │
       └──────┬───────┘    是         否
              │             │         │
              │             ▼         ▼
              │       ┌──────────┐  ┌────────────────┐
              │       │ Human    │  │ 专业 Agent     │
              │       │ Confirm  │  │ 调用 Java 工具 │
              │       └────┬─────┘  └───────┬────────┘
              │            │ 等待用户确认    │
              │            └────中断并保存───┘
              │                              │
              └──────────────────────┬───────┘
                                     ▼
                              ┌──────────────┐
                              │ Quality Gate │
                              └──────┬───────┘
                                     ▼
                              ┌──────────────┐
                              │ Responder    │
                              └──────────────┘
```

人工确认恢复时，Java 先使用 CAS 抢占动作，再将 Checkpoint 和 `confirmed=true` 发送给 Python。Python 从 `human_confirm` 后继续到目标专业 Agent，不重新创建待确认动作。

### 6.3 状态兼容

Python `AgentState` 使用 `TypedDict` 定义，并保持现有 Checkpoint 的 snake_case 字段名称，例如：

- 身份与线程：`thread_id`、`tenant_user_id`、`username`。
- 输入和页面：`user_input`、`current_route`、`current_page_title`。
- 多轮上下文：`turn`、`recent_msgs`、`summary`、`task_type`、`slots`、`missing_slots`、`intermediate`、`phase`。
- 路由：`route_decision`、`intent`、`current_node`、`next_node`。
- 结果：`result_content`、`result_type`、`pending_action_id`、`exit_reason`、`error`。
- 守卫：`iteration`、`node_trace`、`route_trace`、`node_visit_count`、`route_repeat_count`。

第一阶段沿用现有字段，避免切换引擎后历史会话丢失。Python 返回状态前必须移除流对象、客户端、回调等不可序列化的临时字段；Java 只允许保存白名单字段。

## 7. Java/Python 内部接口契约

所有接口统一使用 `/internal/v1` 前缀，不对公网和浏览器暴露。

### 7.1 健康检查

`GET /internal/v1/health`

返回服务状态、图版本、模型依赖是否就绪以及 RAG/Mem0 的降级状态。健康检查不返回密钥和内部地址。

### 7.2 同步执行

`POST /internal/v1/agent/invoke`

请求核心字段：

```json
{
  "request_id": "UUID",
  "idempotency_key": "消息或动作级唯一键",
  "tenant_user_id": 10001,
  "username": "zhangsan",
  "session_id": "sess_xxx",
  "content": "查询今天的检测结果",
  "current_route": "/detection",
  "current_page_title": "检测中心",
  "checkpoint": {},
  "mode": "MESSAGE"
}
```

响应核心字段：

```json
{
  "request_id": "UUID",
  "content": "……",
  "result_type": "TEXT",
  "intent": "DETECTION_QUERY",
  "action": null,
  "checkpoint": {},
  "exit_reason": "COMPLETE",
  "trace": []
}
```

当需要人工确认时，`action` 包含 `action_id`、`intent`、`target_agent`、预览内容和经过校验的参数，`exit_reason` 为 `PENDING_CONFIRMATION`。Java 在事务中保存待确认动作、Checkpoint 和助手预览消息。

### 7.3 流式执行

`POST /internal/v1/agent/stream`

请求体与同步执行一致，Python 返回 SSE。内部事件限定为：

- `status`：节点或阶段状态，不含敏感提示词。
- `chunk`：文本增量。
- `done`：最终结果、动作信息和完整可持久化 Checkpoint。
- `error`：稳定错误码、可展示消息和 `request_id`。

Java 继续向前端发送现有 `connected`、`status`、`chunk`、`done`、`error` 事件。Java 只在收到合法 `done` 后保存最终助手消息和 Checkpoint；连接中断时不把半截回答写成完整消息。

### 7.4 恢复执行

`POST /internal/v1/agent/resume`

请求包含已由 Java 验证归属并抢占成功的 `action_id`、`confirmed`、Checkpoint 和幂等键。取消动作由 Java 直接完成，不调用 Python；确认动作才调用 `resume`。

## 8. Java 内部工具接口

Python 专业 Agent 不直接访问核心数据库，而是调用 Java 暴露的最小能力接口，例如：

```text
POST /internal/v1/agent-tools/detection/query
POST /internal/v1/agent-tools/detection/action
POST /internal/v1/agent-tools/resource/query
POST /internal/v1/agent-tools/resource/action
POST /internal/v1/agent-tools/report/query
POST /internal/v1/agent-tools/report/action
POST /internal/v1/agent-tools/ops/query
```

每个接口使用固定 Pydantic/Java DTO，不提供任意 SQL、任意 URL、反射调用或通用方法名。Java 必须重新校验签名中的 `tenant_user_id`、资源归属、动作权限和参数范围，不能因为调用来自内网就跳过授权。

查询工具允许在网络错误时最多重试两次并指数退避。写工具禁止盲目重试；必须携带 `idempotency_key`，由 Java 保存并复用第一次执行结果，防止超时后重复创建任务或重复修改数据。

## 9. 多租户与安全设计

### 9.1 身份传递

`tenant_user_id` 只由 Java 从当前登录主体生成。Java 调用 Python、Python 调用 Java 工具时均携带：

- `X-Request-Id`
- `X-Timestamp`
- `X-Nonce`
- `X-Signature`

签名使用共享密钥对“请求方法、路径、时间戳、随机数、请求体摘要”计算 HMAC-SHA256。接收端拒绝超出 60 秒时间窗、签名不一致或已使用的随机数；随机数短期记录在 Redis 中防止重放。

### 9.2 数据边界

- 同一企业内共享检测任务、设备、模型和质检数据，具体权限仍由 Java 业务层控制。
- 每个用户的聊天会话、消息、Checkpoint、Mem0 记忆和待确认动作以 `tenant_user_id` 隔离。
- Python 的 RAG 和 Mem0 检索必须同时传入租户/用户作用域，禁止只有 `session_id` 的无身份查询。
- 日志不记录访问令牌、签名密钥、完整提示词、完整记忆和图片预签名地址。
- Python 工具客户端不接受模型生成的目标 URL，从设计上阻断 SSRF。

## 10. 可靠性、重试与回退

### 10.1 运行守卫

迁移后保持现有语义：

- 单轮最多 15 次节点迭代。
- 单节点最多访问 4 次。
- 同一路由最多连续重复 3 次。
- 节点和路由轨迹最多保留 24 项。
- Router 最多重试 2 次；写操作节点不自动重试。

Python 的总执行超时与 Java SSE 连接超时分开配置，默认总上限不超过 300 秒。普通同步请求使用更短的业务超时；超时必须产生稳定错误码并保留 `request_id`。

### 10.2 引擎切换

Java 增加：

```yaml
chat-assistant:
  engine: java       # java 或 python
  fallback-to-java: true
```

上线顺序先保持 `java`，完成契约和影子验证后再切换为 `python`。引擎配置支持通过环境变量覆盖，回滚无需重新构建镜像。

### 10.3 自动回退边界

- 同步查询：Python 在执行任何写操作前不可用，可以回退 Java。
- 流式查询：只允许在向前端发送第一个 `chunk` 前回退；已经输出内容后不能拼接另一引擎的回答，只发送明确错误并允许用户重试。
- 写操作和确认恢复：一旦 Java 已把动作状态改为 `EXECUTING` 或任一工具开始执行，禁止自动切换到 Java 重做。系统依赖幂等键查询执行结果，无法确认时将动作标记为 `FAILED` 并保留审计信息。
- RAG 或 Mem0 不可用：允许降级为无检索/无长期记忆回答，但必须在内部指标中记录，不能跨用户检索替代数据。
- 模型分类失败：回退到现有确定性关键词路由；最终回答不满足质量契约时进入 `fallback`。

## 11. 可观测性

Java 和 Python 使用同一个 `request_id` 贯穿浏览器请求、图节点和业务工具调用。记录：

- 总耗时、首字耗时、模型耗时、工具耗时。
- 目标 Agent、节点轨迹、退出原因和回退原因。
- Python 服务错误率、超时率、Java 回退次数。
- 人工确认创建、抢占、完成和失败数量。
- RAG、Mem0 的命中率和降级次数。

指标标签禁止使用用户名、消息正文和 `session_id`，避免高基数和隐私泄露；排障时通过 `request_id` 关联脱敏日志。

## 12. 迁移步骤

### 阶段一：建立契约和 Python 骨架

- 创建 Python 服务、状态模型、请求签名、健康检查和测试框架。
- 建立 Java Python 客户端和模拟服务契约测试。
- 默认仍使用 Java 引擎。

### 阶段二：迁移只读执行链

- 迁移 Router、Slot Filling、查询型专业 Agent、Quality Gate、Responder 和 Fallback。
- 通过 Java 只读工具接口获取业务数据。
- 对相同测试输入比较 Java/Python 的意图、结果类型和关键事实，不比较措辞是否完全一致。

### 阶段三：迁移流式输出、RAG 和 Mem0

- Java 代理 Python SSE，验证断连、超时和半截回答处理。
- 将 RAG、Mem0 调用迁到 Python，进行两个用户之间的隔离测试。

### 阶段四：迁移人工确认和写操作

- 保留 Java 的待确认 CAS 状态机。
- Python 生成动作计划，Java 持久化；确认后 Python 通过幂等 Java 工具执行。
- 验证重复确认、网络超时和服务重启不会产生重复副作用。

### 阶段五：灰度与收尾

- 在开发和测试环境切换为 Python 引擎，观察错误率和回退率。
- 验证稳定后生产灰度，保留 Java 回退至少一个发布周期。
- 最后另行评审删除 Java StateGraph、旧 Agent Service 和无用 Maven 骨架，不在本次迁移中直接删除。

## 13. 测试设计

### 13.1 Python 单元测试

- 意图路由和关键词纠偏。
- 多轮 `NEW_TASK/SUPPLEMENT/MODIFY/FOLLOWUP/CHITCHAT`。
- 缺失槽位追问及槽位补齐。
- 查询、写操作、人工确认中断与恢复路由。
- 质量门禁、Fallback、节点访问次数和最大迭代守卫。
- 状态序列化白名单和不可序列化字段清理。
- 签名校验、过期请求和重放攻击拒绝。

### 13.2 契约测试

- Java DTO 与 Pydantic Schema 对相同样例均可解析。
- 同步成功、待确认、错误响应的字段兼容。
- SSE `status/chunk/done/error` 顺序及断连行为。
- 历史 Java Checkpoint 可被 Python 加载，Python 新 Checkpoint 可被 Java 保存。

### 13.3 Java 测试

- Python 客户端连接、超时、签名和错误映射。
- 消息和 Checkpoint 只在合法最终结果后写入。
- 人工确认 CAS 防重复执行。
- 首个流式块之前允许回退，之后禁止双引擎拼接。
- 内部工具接口重新执行租户、资源和权限校验。

### 13.4 集成与回归测试

- 两个用户不能读取彼此会话、记忆、Checkpoint 和待确认动作。
- 同一企业的授权用户可以按现有规则共享设备、模型和质检数据。
- Python/Java 重启、模型超时、RAG/Mem0 不可用和工具响应丢失场景。
- 现有 Maven 测试、前端契约测试和核心聊天端到端流程全部通过。

## 14. 完成标准

- 前端无需修改即可完成普通问答、流式问答和人工确认。
- Python LangGraph 覆盖现有 Java StateGraph 的全部节点和退出语义。
- Java 仍是身份、会话、Checkpoint、待确认状态和核心业务数据的唯一可信边界。
- Python 不直接访问核心业务表，所有业务工具均有固定契约、权限校验和审计。
- 重复确认、网络重试和超时不会造成重复写操作。
- 可以通过配置在 Python 和 Java 引擎间切换，并符合流式与写操作的回退边界。
- 多租户隔离、契约、故障恢复和现有项目回归测试全部通过。
