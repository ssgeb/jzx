# Harness 多智能体编排与分层记忆

> 返回总览：[项目亮点与面试指南](../项目亮点与面试指南.md)

## 1. 本章目标

本章独立讲解 Harness Agent 如何使用 Python Deep Agents 进行任务规划、上下文管理和专业子 Agent 委派，以及 Java 安全边界、MySQL、Mem0 和本地 Markdown 知识库如何协同。Deep Agents 底层仍使用 LangGraph；原有确定性 LangGraph 保留为写操作确认、无模型配置和异常降级链路。

### 1.1 术语翻译与作用

| 英文术语 | 中文名称 | 在本项目中的作用 |
| --- | --- | --- |
| Harness Agent | 智能体编排框架 | 组织多个业务智能体协同处理用户请求 |
| Deep Agents | 深度智能体 Harness | 内置任务规划、上下文压缩和子 Agent 委派的主编排器 |
| Agent | 智能体 | 负责检测、资源、报表或运维等一种业务能力 |
| `write_todos` | 任务清单工具 | Harness 主 Agent 对复杂问题分步并跟踪状态 |
| `task` | 子 Agent 委派工具 | 把隔离的子任务交给检测、资源、报表或运维专家 |
| LangGraph StateGraph | LangGraph 状态图 | Deep Agents 的底层执行时，也承担确定性降级流程 |
| Checkpoint | 检查点 | 将会话执行状态持久化，支持中断后恢复 |
| RouterNode | 路由节点 | 识别用户意图并选择目标智能体 |
| SlotFillingNode | 槽位补全节点 | 发现缺失参数并继续向用户追问 |
| HumanConfirmNode | 人工确认节点 | 修改数据前暂停流程并等待用户确认 |
| ResponderNode | 回答生成节点 | 汇总工具结果并组织最终回答 |
| FallbackNode | 降级处理节点 | 在异常或循环超限时生成可解释的兜底回答 |
| RAG | 检索增强生成 | 从项目知识库检索资料后辅助模型回答 |
| SSE | 服务器发送事件 | 后端持续向浏览器推送流式回答 |
| Mem0 | 长期记忆组件 | 按租户用户和会话作用域保存偏好与历史信息 |
| Local Markdown RAG | 本地 Markdown 知识检索 | Python 服务对可信文档分块、检索并注入上下文 |
| ChromaDB | 向量知识库 | 旧 Java RAG 链路支持的可选向量检索，当前 Python 主链路不依赖它 |
| HMAC | 消息鉴别码 | 为 Python 调用 Java 内部工具的 HTTP 请求签名 |

## 2. 业务问题

单一大模型直接处理全部业务时容易出现：

- 提示词不断膨胀，检测、资源和报表规则互相干扰。
- 多轮对话丢失任务编号、时间范围等已收集参数。
- 修改操作未经确认直接进入业务服务。
- 路由与槽位补全循环调用，持续消耗模型 Token。
- 回答脱离数据库和企业知识库。

## 3. 模块框架图

### 3.0.1 当前 Deep Agent 主链路

~~~text
用户输入问题或操作指令
        │
        ▼
┌────────────────────────────────────────────────────────────┐
│  步骤一：Java 安全边界                                      │
│                                                              │
│  租户/用户/会话校验 → 限流 → 恢复 MySQL Checkpoint          │
│  组装带 HMAC 签名、防重放与幂等键的 Python 请求             │
└──────────────────────────┬───────────────────────────────────┘
                           │
                      是否为写操作？
                           │
                    ┌──────┴──────┐
                    │             │
                   是             否
                    │             │
                    ▼             ▼
┌───────────────────────────┐  ┌───────────────────────────┐
│ 确定性 LangGraph       │  │ 步骤二：Deep Agent      │
│ 槽位补全 → 人工确认   │  │ 加载本地 RAG + Mem0      │
│ CAS 抢占后调用 Java 写工具 │  │ write_todos 分解复杂目标  │
└─────────────┬─────────────┘  └─────────────┬─────────────┘
              │                           │ task 只能委派
              │                           ▼
              │      检测子 Agent / 资源子 Agent / 报表子 Agent / 运维子 Agent
              │                           │
              │                 各自仅一个 Java 只读工具
              │                           │ HMAC HTTP
              │                           ▼
              │                 Java 租户数据与业务 Service
              │                           │
              └───────────────────────────┤
                                          ▼
┌─────────────────────────────────────────────────────────────┐
│  步骤三：主 Agent 汇总 → 工具证据质量门 → 保存消息 → HTTP/SSE   │
│  无有效工具证据、Deep Agent 不可用或异常 → 确定性 LangGraph 降级 │
└────────────────────────────────────────────────────────────┘
~~~

### 3.0.2 写操作与降级状态图

下图为原有确定性 LangGraph，当请求包含写意图、Deep Agent 未配置或执行失败时使用：

~~~text
用户输入问题或操作指令
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤一：装载三层上下文                                      │
│                                                              │
│  MySQL 检查点（Checkpoint）：恢复会话状态与最近消息          │
│  Python 本地 RAG：检索项目 Markdown 公共知识            │
│  Mem0 长期记忆：补充当前用户的偏好与历史信息                 │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤二：路由节点（RouterNode）识别意图并选择执行路径        │
│                                                              │
│  检测任务 → 检测智能体          资源调度 → 资源智能体         │
│  报表查询 → 报表智能体          运维诊断 → 运维智能体         │
└──────────────────────────┬───────────────────────────────────┘
                           │
                      参数是否完整？
                           │
                    ┌──────┴──────┐
                    │             │
                   否             是
                    │             │
                    ▼             ▼
           槽位补全节点        进入目标智能体
           追问缺失参数             │
                    │               ▼
                    └───────> 是否属于修改操作？
                                    │
                             ┌──────┴──────┐
                             │             │
                            是             否
                             │             │
                             ▼             ▼
┌───────────────────────────────┐   ┌──────────────────────────┐
│  步骤三A：人工确认            │   │  步骤三B：直接执行      │
│                               │   │                          │
│  保存待确认动作与检查点       │   │  智能体调用业务工具      │
│  中断状态图，确认后恢复执行   │   │  返回结构化执行结果      │
└───────────────┬───────────────┘   └────────────┬─────────────┘
                │                                │
                └───────────────┬────────────────┘
                                ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤四：回答节点统一组织答案并通过 SSE 流式输出             │
│                                                              │
│  异常或循环超限 → 降级节点 → 返回可解释的兜底结果            │
└──────────────────────────────────────────────────────────────┘
~~~

### 3.1 Agent 之间的消息传递与沟通机制

#### 3.1.1 核心结论

查询链路中，Harness 主 Agent 通过 Deep Agents 内置 `task` 工具把子任务交给专业子 Agent；子 Agent 在隔离上下文中完成工作，只向主 Agent 返回一份最终报告。子 Agent 之间没有点对点消息通道。写操作和降级链路仍使用“中心路由 + `AgentState`”状态图。只有跨语言边界使用 HTTP：每个 Python 专业子 Agent 通过 HMAC 签名的固定只读接口调用 Java 业务能力。

| 英文术语 | 中文名称 | 项目中的含义 |
| --- | --- | --- |
| Blackboard Pattern | 黑板模式 | 多个节点围绕同一份共享状态协作 |
| AgentState | 智能体状态 | 一次请求在各节点之间传递的统一数据容器 |
| RouterNode | 路由节点 | 判断意图、目标 Agent 和是否需要确认 |
| Conditional Edge | 条件边 | 根据状态字段决定下一个执行节点 |
| Checkpoint | 检查点 | 把状态保存到 MySQL，支持跨请求恢复 |
| Slot | 槽位/业务参数 | 任务编号、设备编号、时间范围等执行条件 |

#### 3.1.2 消息传递框架

~~~text
用户输入自然语言消息
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤一：Java 恢复会话，Python 创建 AgentState             │
│                                                              │
│  写入用户输入、用户身份、会话编号和页面上下文                │
│  恢复最近消息、会话摘要、已收集槽位和中间结果                │
│  context 节点注入本地 RAG 公共知识和 Mem0 用户记忆       │
└──────────────────────────┬───────────────────────────────────┘
                           │ 同一个 AgentState
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤二：RouterNode 生成路由决定                             │
│                                                              │
│  targetAgent：目标智能体                                     │
│  intent：查询或修改意图                                      │
│  confirmationRequired：是否需要人工确认                      │
│  slots / missingSlots：已有参数和缺失参数                    │
└──────────────────────────┬───────────────────────────────────┘
                           │
                     参数是否完整？
                           │
                    ┌──────┴──────┐
                    │             │
                   否             是
                    │             │
                    ▼             ▼
             槽位补全节点       查询还是修改？
             生成追问消息           │
                              ┌─────┴─────┐
                              │           │
                             查询         修改
                              │           │
                              ▼           ▼
                       目标专业 Agent   人工确认节点
                              │           │
                              │      保存待确认动作和检查点
                              │           │
                              │      用户确认后恢复状态图
                              │           │
                              └─────┬─────┘
                                    ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤三：专业 Agent 读取状态并调用业务服务                   │
│                                                              │
│  检测 Agent / 资源 Agent / 报表 Agent / 运维 Agent           │
│  将执行内容、结果类型、意图和退出原因写回 AgentState         │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤四：编排服务读取结果                                    │
│                                                              │
│  保存聊天消息和最新检查点                                    │
│  普通接口返回文字或业务卡片，流式接口通过 SSE 持续输出       │
└──────────────────────────────────────────────────────────────┘
~~~

#### 3.1.3 AgentState 传递的关键信息

| 状态键 | 中文含义 | 主要写入者 | 主要读取者 |
| --- | --- | --- | --- |
| `user_input` | 当前用户输入 | 编排服务 | 路由节点、专业 Agent |
| `username` | 当前用户身份 | 编排服务 | 专业 Agent、确认节点 |
| `thread_id` | 会话编号 | 编排服务 | 检查点、确认节点 |
| `recent_msgs` | 最近多轮消息 | 上下文恢复逻辑 | 路由节点、专业 Agent |
| `summary` | 会话摘要 | 上下文构建器 | 路由节点 |
| `rag_context` | 公共知识检索结果 | Python context 节点 | 各专业 Agent |
| `user_memories` | 按用户与会话检索的长期记忆 | Python context 节点 | 路由节点、各专业 Agent |
| `route_decision` | 路由决定 | RouterNode | StateGraph、确认节点 |
| `intent` | 查询或修改意图 | RouterNode | 条件边、专业 Agent |
| `slots` | 已收集业务参数 | 路由节点、槽位节点 | 后续专业 Agent |
| `missing_slots` | 缺少的业务参数 | RouterNode | SlotFillingNode |
| `intermediate` | 多轮中间结果 | 各业务节点 | 后续节点 |
| `confirmed` | 是否已经人工确认 | 恢复流程 | 确认节点、专业 Agent |
| `pending_action_id` | 待确认动作编号 | HumanConfirmNode | 编排服务、前端 |
| `result_content` | 最终内容 | 专业 Agent 或回答节点 | 编排服务 |
| `result_type` | 文本、业务卡片等结果类型 | 专业 Agent | 编排服务、前端 |
| `error` | 节点执行异常 | 状态图执行器 | FallbackNode |

`AgentState` 为每次请求单独创建，Python 中以 `TypedDict` 定义状态契约。当前 LangGraph 按条件边顺序调度节点，因此现阶段是可追踪的顺序编排，不是多个 Agent 同时讨论。`rag_context`、`user_memories` 等上下文是本轮临时数据，不写入 Checkpoint，避免过期知识和敏感记忆在后续请求中重放。

#### 3.1.4 三种典型沟通链路

查询链路：

~~~text
“查询 TASK-1001 的检测结果”
  → context 节点检索 RAG 与 Mem0
  → router 写入 DETECTION_QUERY
  → LangGraph 选择 detection_agent
  → detection_agent 调用 Java 受限工具并写回 result_content
  → 编排服务将结果保存并返回前端
~~~

参数补全链路：

~~~text
“开始检测”
  → RouterNode 发现缺少 taskId
  → phase=COLLECTING，missing_slots=[taskId]
  → SlotFillingNode 生成“请提供任务编号”
  → 下一轮恢复已有状态并合并用户补充的 taskId
  → 参数完整后进入 DetectionAgentNode
~~~

写操作确认链路：

~~~text
“把 TASK-1001 标记为返工”
  → RouterNode 写入 DETECTION_ACTION
  → HumanConfirmNode 保存待确认动作
  → MySQL 保存 AgentState Checkpoint 并暂停
  → 用户点击确认，恢复时写入 confirmed=true
  → 根据 route_decision 进入 DetectionAgentNode
  → 执行返工并返回结果
~~~

#### 3.1.5 持久化与实时输出

- MySQL `Checkpoint` 保存可序列化的 `AgentState`，用于跨请求、应用重启和人工确认后的恢复。
- `chat_message` 保存用户可见的完整聊天历史，它与机器执行状态不是同一类数据。
- Python 本地 Markdown RAG 提供项目公共知识，Mem0 提供按用户和会话隔离的长期记忆，两者作为上下文注入，不承担 Agent 节点调度。RAG 或 Mem0 不可用时仅标记上下文降级，业务查询仍可继续。
- SSE 流式消费者只在当前 HTTP 请求中有效，被标记为临时字段，不会写入 Checkpoint。
- Kafka 只用于 Spring Boot 与 Python 推理 Worker 之间的检测任务消息，不参与 Java 内部 Agent 通信。

#### 3.1.6 当前能力边界

当前实现属于受限 Supervisor 型多 Agent：

- Harness 主 Agent 可对复杂查询使用 `write_todos` 分步，并通过 `task` 委派一个或多个专业 Agent。
- Agent 之间没有点对点消息通道。
- 专业子 Agent 只返回一份精简报告，由 Harness 主 Agent 统一汇总；当前没有投票或裁判模型。
- 子 Agent 是短生命、隔离上下文，不保留自己的私有长期状态。
- 写操作不进入 Deep Agent 工具循环，必须走 Java 人工确认和 CAS 抢占。

### 3.2 主 Agent 与子 Agent 如何协同

#### 3.2.1 项目中的“主 Agent”是什么

项目已实现受限的 Harness Supervisor。它可以规划和委派查询任务，但不是拥有任意工具的通用自治 Agent。“主 Agent”由 Java 边界层与 Python Deep Agents 调度层共同承担：

| 组件 | 主 Agent 职责 |
| --- | --- |
| Java `AgentOrchestratorServiceImpl` | 校验租户、用户和会话，恢复 MySQL Checkpoint，调用 Python 并保存最终消息 |
| Python `HarnessDeepAgent` | 装载 RAG 与 Mem0，使用 `write_todos` 规划，通过 `task` 委派并汇总子 Agent 结论 |
| Python `AgentGraph` | 拦截写操作，负责槽位补全、人工确认和 Deep Agent 异常时的确定性降级 |

检测、资源、报表和运维是四个 Deep Agents 专业子 Agent。子 Agent 只处理自己负责的业务，工具列表中只有对应领域的 Java 只读接口，不能绕过主 Agent 调度另一个子 Agent。

`SlotFillingNode`、`HumanConfirmNode`、`ResponderNode` 和 `FallbackNode` 属于控制节点：它们负责补参数、人工确认、统一回答和异常兜底，不属于专业业务子 Agent。

#### 3.2.2 主从协同框架

~~~text
用户发送消息
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│  Java 边界层：AgentOrchestratorServiceImpl                  │
│                                                              │
│  校验租户和会话 → 限流 → 恢复 Checkpoint                   │
│  组装请求、恢复允许跨轮保留的 AgentState 字段              │
│  调用已签名的 Python 内部编排接口                         │
└──────────────────────────┬───────────────────────────────────┘
                           │ 共享 AgentState
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  Python 主 Agent 调度内核：Deep Agents + LangGraph         │
│                                                              │
│  注入 RAG/Mem0，write_todos 规划，task 委派子 Agent     │
│  简单问题委派一个，跨域问题可委派多个专家               │
└──────────────────────────┬───────────────────────────────────┘
                           │
             ┌─────────────┼─────────────┬─────────────┐
             │             │             │             │
             ▼             ▼             ▼             ▼
       检测子 Agent   资源子 Agent   报表子 Agent   运维子 Agent
             │             │             │             │
             └─────────────┴──────┬──────┴─────────────┘
                                  │ 写回同一个 AgentState
                                  ▼
┌──────────────────────────────────────────────────────────────┐
│  resultContent + resultType + intent + exitReason            │
│  → ResponderNode 统一结果                                    │
│  → 主 Agent 保存消息和 Checkpoint                            │
│  → HTTP 或 SSE 返回前端                                      │
└──────────────────────────────────────────────────────────────┘
~~~

协作过程中没有“子 Agent A 直接给子 Agent B 发消息”。如果检测子 Agent 产生的信息需要后续节点使用，它必须写入 `AgentState`；状态图再根据条件边把状态交给下一个节点。这样可以记录每次路由、统一持久化，也避免专业 Agent 形成不可观察的私有调用链。

#### 3.2.3 一次协同执行的六个步骤

1. 编排服务从登录态构建 `TenantContext`，校验会话属于当前用户。
2. 编排服务从 MySQL Checkpoint 选择性恢复允许跨轮保留的字段，再注入本轮输入。
3. Python 安全门先检查写意图：写操作转确定性确认流程，只有查询进入 Deep Agent。
4. Harness 主 Agent 检索本地 RAG 和 Mem0，必要时使用 `write_todos` 分解任务，再通过 `task` 委派专业子 Agent。
5. 子 Agent 通过 HMAC 签名 HTTP 调用自己唯一的 Java 只读工具，向主 Agent 返回精简结论。
6. 回答节点和编排服务统一保存结果，通过普通 HTTP 或 SSE 输出。

Deep Agents 使用隔离上下文执行子 Agent，主 Agent 只接收每个子 Agent 的最终报告，避免把大量中间工具结果塞入主上下文。确定性 `AgentState` 仍是 Java–Python 持久化契约和写操作恢复载体。

#### 3.2.4 YAML 子智能体弹性加载

检测、资源、报表和运维子 Agent 已从 Python 硬编码改为受信 YAML 配置。`config/subagents/` 目录采用“一 Agent 一 YAML”：每个文件声明自己的 `id`、名称、启停状态、职责、Skills 和工具；`agent_config.py` 定义严格配置模型和安全约束，独立的 `loader.py` 负责目录扫描、YAML 防护、合并和热加载。Python 只有在全部文件合并验证成功后，才把启用的子 Agent 连接到 Harness 主 Agent。

~~~text
修改、增加或删除某个子 Agent YAML
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤一：读取并检测配置变化                                  │
│                                                              │
│  按文件名排序，计算文件名与内容的联合 SHA-256 摘要           │
│  摘要未变化 → 复用目录；摘要变化 → 重新解析全部文件         │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤二：严格配置校验                                        │
│                                                              │
│  每个文件只允许一个 Agent，文件名必须等于 Agent name         │
│  拒绝未知字段、重复键、冲突定义、锚点/别名和重复 Agent      │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤三：代码工具白名单二次授权                              │
│                                                              │
│  YAML 工具名 → TRUSTED_TOOL_BINDINGS → Java Agent/operation  │
│  未注册工具、写操作或伪造后端工具绑定均拒绝加载              │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤四：构建并连接 Harness                                  │
│                                                              │
│  职责 + Skill 指令 → 子 Agent system_prompt                  │
│  工具引用 → 子 Agent 独立工具集                              │
│  enabled 子 Agent → 主 Agent 的 task 委派目录                │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
                配置无效？────是────> 标记配置无效并走 LangGraph 降级
                           │否
                           ▼
                  主 Agent 规划、委派并汇总结论
~~~

YAML 中的核心结构如下：

| 配置区 | 关键字段 | 作用 |
| --- | --- | --- |
| `skills` | `description`、`instructions` | 定义可复用的可信业务方法和回答约束 |
| `tools` | `target_agent`、`operation`、`description`、`human_intervention` | 声明工具用途和调用前人工介入策略，但不能创建代码中不存在的工具 |
| `subagent` | `id`、`name`、`enabled` | 每个文件只定义一个子 Agent 的身份和启停状态 |
| `subagent.responsibilities` | 多条职责 | 明确负责范围、缺参处理和禁止越界事项 |
| `subagent.skills` | Skill 名称列表 | 只能引用本文件声明的业务指令，并将其注入系统提示词 |
| `subagent.tools` | 工具名称列表 | 给该子 Agent 分配最小权限工具集 |

所谓“弹性加载”包括三点：修改某个 Agent 的职责或 Skill 指令后，下一次请求按目录联合摘要热加载；把 `enabled` 改为 `false` 可从主 Agent 委派目录摘除该子 Agent；增加一个名称匹配的新 YAML 即可创建新子 Agent。每个文件必须自包含它引用的 Skill 与工具；复用时要在新文件中给出完全一致的定义。真实工具仍不能仅靠 YAML 创建，必须先进入 Python 工具白名单并通过 Java 内部接口实现，避免配置文件变成任意代码执行入口。

YAML Skill 与网络下载 Skill 是两条不同的信任链：前者随项目代码审核和发布，可以进入提示词；后者下载后仍为 `QUARANTINED`，不会因为名称出现在 YAML 中就自动激活。

#### 3.2.5 YAML 工具调用前人工介入

工具定义中的 `human_intervention` 把风险控制从提示词约定提升为强制配置和服务端代码校验：

| 字段 | 中文含义 | 校验规则 |
| --- | --- | --- |
| `required` | 是否需要人工确认 | 为 `true` 时，确认前绝不调用 Java 工具 |
| `timing` | 介入时机 | 当前只允许 `before_tool`（工具调用之前） |
| `risk_level` | 风险等级 | 可配置低、中、高；高风险不能关闭人工介入 |
| `approval_message` | 确认提示 | 需要确认时必填，向用户说明将访问什么信息及其风险 |

当前策略不是“所有工具一律弹窗”。检测、资源和报表查询均受租户身份和固定只读接口约束，配置为低风险自动执行；`query_ops` 会查询服务健康、消息链路和故障诊断信息，可能包含敏感运维信息，因此配置为高风险并在调用前暂停。

~~~text
子 Agent 准备调用 YAML 中声明的工具
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤一：读取并校验人工介入策略                              │
│                                                              │
│  required=false → 进入受限 Java 只读工具                    │
│  required=true  → 此时不发送任何 Java 工具请求              │
└──────────────────────────┬───────────────────────────────────┘
                           │ 需要人工确认
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤二：生成并持久化待确认动作                              │
│                                                              │
│  保存 actionId、工具名、问题、风险级别和工具定义 SHA-256    │
│  返回 PENDING_ACTION，由 Java/MySQL 保存 Checkpoint          │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
                    用户确认还是取消？
                    ┌──────┴──────┐
                    │             │
                   取消           确认
                    │             │
                    ▼             ▼
             不调用工具     ┌─────────────────────────────────┐
                            │  步骤三：恢复前安全复核           │
                            │                                 │
                            │  actionId 是否一致              │
                            │  工具是否仍存在                  │
                            │  YAML 定义摘要是否未变化         │
                            │  当前策略是否仍要求人工介入      │
                            └───────────────┬─────────────────┘
                                            │ 全部通过
                                            ▼
                            ┌─────────────────────────────────┐
                            │  步骤四：只执行一次受限工具       │
                            │                                 │
                            │  固定租户/用户/会话身份          │
                            │  HMAC 签名 + 幂等键调用 Java     │
                            │  登记工具证据并清除待确认状态    │
                            └─────────────────────────────────┘
~~~

项目没有把这项安全能力仅实现成 Python 进程内的临时暂停。Deep Agent 图会在每次 HTTP 请求时重建，而用户确认可能发生在另一次请求、容器重启后或另一个 Python 实例上；因此这里复用已有的 Java/MySQL 待确认动作和 Checkpoint，获得跨请求、跨实例恢复能力。恢复时重新计算 YAML 工具定义摘要，可以避免管理员在等待确认期间更改工具目标、风险等级或提示内容后，旧确认仍被用于新配置。

### 3.3 如何保证 Agent 执行流程安全

#### 3.3.1 五层安全边界

| 安全层 | 项目措施 | 解决的问题 |
| --- | --- | --- |
| 身份入口 | `TenantPrincipal` 转换为 `TenantContext`，并校验会话所有者 | 防止越权使用别人的会话 |
| 状态传播 | `tenant_user_id` 写入 `AgentState`，缺失时 `requireTenantContext()` 直接失败 | 防止节点脱离真实身份执行 |
| 参数完整性 | Slot Filling 收集任务号、工单号等必需参数 | 防止基于不完整参数调用业务服务 |
| 高风险操作 | 修改意图经过 `HumanConfirmNode`；YAML 高风险查询工具也在真正调用前生成持久化确认动作 | 防止模型直接执行写操作或读取敏感运维信息 |
| 工具最小权限 | 主 Agent 只拥有 `task` 和 `write_todos`；子 Agent 的 YAML 工具列表还必须通过 Python 代码白名单 | 防止提示注入或配置篡改把查询能力扩大为文件、命令或业务写权限 |
| 运行边界 | LangGraph 递归上限、节点访问次数和外部调用超时 | 防止死循环和资源无限占用 |
| 跨语言边界 | HMAC 签名、时间窗、防重放和幂等键 | 防止内部工具被伪造请求或重复执行 |

~~~text
用户请求
    │
    ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤一：身份与会话校验                                    │
│  requireTenant + verifySessionOwner + 每用户请求限流         │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤二：意图与工具边界校验                                │
│  写操作 → 确定性流程；查询 → Deep Agent 规划与委派          │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
                    查询还是修改？
                     ┌─────┴─────┐
                     │           │
                    查询         修改
                     │           │
                     ▼           ▼
               受限 Deep Agent  保存待确认动作
                                 │
                                 ▼
                         用户明确确认后恢复
                     └─────┬─────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤三：运行守卫与降级                                    │
│  模型超时、Deep Agent 异常或状态图超限 → 确定性 Fallback   │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
             保存 Checkpoint、审计状态并返回结果
~~~

#### 3.3.2 高风险动作的并发保护

第一次进入 `HumanConfirmNode` 时只生成操作预览和 `PENDING` 动作，不执行真实修改。用户确认时，数据库使用条件状态更新抢占执行权：

~~~text
PENDING
   │ 用户确认，且 expectedStatus=PENDING 更新成功
   ▼
EXECUTING ──────执行成功──────> COMPLETED
   │
   └────────执行异常──────────> FAILED

PENDING ───────用户取消────────> CANCELLED
~~~

两个确认请求同时到达时，只有一个能够把 `PENDING` 改为 `EXECUTING`；另一个会收到“正在处理或已处理”，从而降低重复点击造成重复业务写入的风险。

#### 3.3.3 运行守卫的真实参数

| 守卫 | 当前默认值 | 触发后的处理 |
| --- | ---: | --- |
| Deep Agent 最大迭代次数 | 40 | 终止自治查询，整轮降级到确定性 LangGraph |
| DeepSeek 单次模型调用超时 | 60 秒 | Deep Agent 失败并进入确定性降级链路 |
| DeepSeek 模型自动重试 | 1 次 | 只重试模型层瞬时故障，不自动重试业务工具 |
| 确定性 LangGraph 最大递归步数 | 15 | 终止超限调度，请求按异常处理 |
| 确定性流程单节点最大访问次数 | 4 | 写入 `error`，后续进入 Fallback |
| 节点轨迹保留长度 | 24 | 只保留最近轨迹，控制 Checkpoint 大小 |
| Python 调用 Java 工具超时 | 15 秒 | HTTP 异常写入 `error` 并进入 Fallback |
| Mem0 连接/读取超时 | 2 秒 / 5 秒 | 仅降级记忆上下文，不中断业务 Agent |

DeepSeek 模型层最多自动重试一次，但业务工具不自动重试。Deep Agent 整体异常时，服务使用同一份已验证请求状态重新进入确定性 LangGraph；RAG 或 Mem0 异常只移除相应上下文，不中断主业务。写操作从一开始就不进入 Deep Agent，因此不会因为查询规划重试而被重复执行。如后续为工具增加重试，应只对已证明幂等的查询或携幂等键的写入开启。

#### 3.3.4 回答达到什么条件才能返回

主 Agent 的提示词要求以 Java 业务工具结果为准，但安全性不能只依赖提示词。代码还执行以下确定性质量门：

1. 子 Agent 必须实际调用自己绑定的 Java 只读工具，模型直接生成的无工具回答不予采用。
2. Java 工具必须成功返回非空业务内容；异常或空结果不能登记为有效证据。
3. 每次成功调用记录专业 Agent、工具名称和问题摘要哈希，不保存完整问题或工具原文，形成 `data_context.deepAgentEvidence`。
4. 最终回答必须非空，并在轨迹中经过 `deep_agent_quality_gate`。
5. 任一条件不满足就抛出编排异常，由 `AgentService` 使用原始已验证状态进入确定性 LangGraph，而不是把可疑回答返回给用户。

这套质量门证明“回答读取过可信业务数据”，但不宣称能够数学证明每句话都完全正确。最终准确性仍依赖 Java 数据质量、专业子 Agent 提示词和业务测试，因此项目同时保留操作人工确认、审计记录与降级链路。

### 3.4 Agent 失败后如何恢复

#### 3.4.1 失败处理与恢复框架

~~~text
节点开始执行
    │
    ▼
执行成功？──────────────是──────────────> 写回 AgentState → 下一节点
    │
    否
    ▼
捕获异常并写入 error
    │
    ▼
┌──────────────────────────────────────────────────────────────┐
│  写入 error 与 exitReason                                   │
│  执行 Fallback，返回“不基于不完整信息编造结果”的说明       │
│  Python 返回安全快照，Java 保存 Checkpoint 和轨迹           │
└──────────────────────────┬───────────────────────────────────┘
                           │
                  用户稍后重新发起请求
                  或从待确认检查点恢复
~~~

#### 3.4.2 不同失败场景的恢复策略

| 失败场景 | 当前处理 | 恢复方式 |
| --- | --- | --- |
| 路由模型暂时不可用 | 降级为确定性关键词路由 | 无需重试即可继续编排 |
| RAG 或 Mem0 暂时不可用 | 记录 `context_degraded` 并使用空上下文 | 业务 Agent 继续执行，Mem0 写入也不阻塞主响应 |
| Deep Agent 未调用工具或工具返回空内容 | 质量门拒绝无证据回答 | 使用同一请求状态降级到确定性 LangGraph |
| 节点最终执行失败 | 写入错误，进入 Fallback，保存检查点 | 用户补充信息或重新发起请求 |
| 状态图递归或节点访问超限 | LangGraph 或节点守卫终止路径 | 返回异常或 Fallback，避免继续消耗资源 |
| 等待人工确认 | 保存当前节点、状态和待确认动作后暂停 | 用户确认后执行 `resume(tenant, sessionId, confirmed=true)` |
| 应用实例重启 | 状态保存在 MySQL `state_json` | 新实例按租户和会话加载 Checkpoint |
| 确认后的业务执行失败 | 动作从 `EXECUTING` 更新为 `FAILED` 并记录错误摘要 | 用户查看失败信息后重新发起，避免把失败伪装成成功 |
| 普通对话 Checkpoint 读取失败 | 记录警告并创建本轮新状态 | 当前问题仍可继续回答，但旧槽位和执行阶段无法精确恢复 |
| 确认恢复时找不到 Checkpoint | 直接抛出“无法恢复”错误 | 不允许缺少执行状态时继续高风险修改 |

#### 3.4.3 Checkpoint 恢复为什么安全

恢复不是只凭 `sessionId` 读取状态。`MySqlCheckpointer` 使用 `tenant.userId + threadId` 查询和更新，加载成功后还会把当前登录用户的 `userId` 和 `username` 重新写入状态。缺少租户用户 ID 时，节点调用 `requireTenantContext()` 会立即失败，不会退化成只按会话号执行。

~~~java
AgentState state = checkpointer.load(tenant, threadId);
if (state == null) {
    throw new StateGraphException("无法恢复：未找到 checkpoint");
}
state.set(AgentState.KEY_TENANT_USER_ID, tenant.userId());
state.set(AgentState.KEY_USERNAME, tenant.username());
state.setAll(Map.of(AgentState.KEY_CONFIRMED, true));
~~~

#### 3.4.4 当前恢复能力边界

- Python 在单次内部请求中完成节点调度，并把过滤掉临时字段的快照返回 Java；Java 在本轮结束或等待确认时保存 Checkpoint，并非对每个 Python 节点做跨进程持久化。
- Checkpoint 保存失败时当前实现记录警告，不会回滚已经完成的外部业务调用，因此不能宣传为“任意故障都能无损恢复”。
- 项目没有通用的跨 Agent 分布式事务和自动补偿框架。业务修改是否回滚仍由具体 Service 的事务边界决定。
- 普通对话恢复失败可以降级为新状态；高风险确认恢复失败则采用失败关闭，不会猜测原动作继续执行。
- 当前主要恢复粒度是“会话状态图和人工确认动作”，不是对任意节点进行指令级断点续跑。
- 当前测试已覆盖轨迹、Checkpoint 过滤、确认恢复、RAG/Mem0 降级与租户用户隔离；业务工具自动重试尚未开启。

## 4. LangGraph 状态设计

AgentState 是节点之间传递的统一状态容器。核心状态包括：

| 状态 | 含义 |
| --- | --- |
| `thread_id` / `username` / `tenant_user_id` | 会话、用户与租户用户上下文 |
| `recent_msgs` | 最近多轮消息 |
| `slots` | 已收集业务参数 |
| `missing_slots` | 尚缺少的参数 |
| `intent` / `route_decision.targetAgent` | 意图和目标智能体 |
| `phase` | 收集参数、执行、等待确认等阶段 |
| `pending_action_id` | 待确认动作编号 |
| `current_node` / `exit_reason` | 当前节点与退出原因 |
| `rag_context` | 公共知识检索结果，仅在本轮内使用 |
| user_memories | 用户长期记忆 |
| `context_degraded` | RAG 或 Mem0 不可用时的降级标记 |

## 5. 数据库存储

### 5.1 chat_session

| 字段 | 作用 |
| --- | --- |
| session_id | StateGraph threadId |
| username | 会话归属用户 |
| state_json | AgentState 序列化内容 |
| checkpoint_version | 检查点版本 |
| checkpoint_node | 保存时所在节点 |
| checkpoint_exit_reason | 中断或退出原因 |
| checkpoint_updated_at | 最近保存时间 |

### 5.2 chat_message

| 字段 | 作用 |
| --- | --- |
| session_id | 关联会话 |
| role | user 或 assistant |
| message_type | TEXT、业务卡片、待确认卡片 |
| content | 用户可见内容 |
| intent | 本轮识别意图 |
| action_id | 对应待确认动作 |

### 5.3 chat_pending_action

动作状态机：

~~~mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> EXECUTING: 用户确认且抢占成功
    PENDING --> CANCELLED: 用户取消
    EXECUTING --> COMPLETED: 业务执行成功
    EXECUTING --> FAILED: 业务执行异常
~~~

## 6. 核心代码

### 6.1 恢复上下文并覆盖当前身份

文件：`python_assistant_service/app/state.py`

~~~python
state = _checkpoint_state(request.checkpoint)
state.update(
    thread_id=request.session_id,
    user_input=request.content,
    username=request.username,
    tenant_user_id=request.tenant_user_id,
    request_id=request.request_id,
    idempotency_key=request.idempotency_key,
)
~~~

代码先过滤 Java 传入的 Checkpoint，再用当前已验证请求中的会话和身份覆盖历史值。`request_id`、幂等键、RAG 与 Mem0 上下文都是临时字段，不写回 Checkpoint。

### 6.2 注入知识与记忆

~~~python
async def _context(self, state: AgentState) -> dict[str, Any]:
    rag_context = await self._knowledge.retrieve(state["user_input"])
    memories = await self._memory.search(
        state["tenant_user_id"],
        state["thread_id"],
        state["user_input"],
        self._settings.memory_top_k,
    )
    return {
        "rag_context": rag_context,
        "user_memories": memories,
        "user_memory_context": format_memories(memories),
    }
~~~

RAG 面向公共知识；Mem0 面向按 `tenant_user_id + session_id` 隔离的用户长期事实；Checkpoint 面向工作流状态；`chat_message` 面向完整聊天历史。实际代码中两次检索分别捕获异常，因此任意一个上下文源失败都不会阻断业务查询。

### 6.3 创建 Harness 专用 Deep Agent

文件：`python_assistant_service/app/deep_agent.py`

~~~python
register_harness_profile(
    "deepseek",
    HarnessProfile(
        excluded_tools=(
            "ls", "read_file", "write_file", "edit_file",
            "glob", "grep", "execute",
        ),
        general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False),
    ),
)

graph = create_deep_agent(
    model=model,
    tools=[],
    system_prompt=system_prompt,
    subagents=[detection_agent, resource_agent, report_agent, ops_agent],
)
~~~

主 Agent 的业务工具列表为空，只保留 Deep Agents 提供的任务清单 `write_todos` 和子任务委派 `task`。文件读写、全文搜索、Shell 执行以及默认通用子 Agent 全部被禁用。四个专业子 Agent 分别只绑定检测、资源、报表或运维查询工具；工具闭包从经过校验的请求状态中固定取得企业、用户、会话、请求和幂等信息，模型不能自行伪造这些身份字段。

### 6.4 工具证据质量门

~~~python
content = result.get("content") or result.get("message")
if content is None or not str(content).strip():
    raise RuntimeError(f"{agent} 业务工具返回空内容")

evidence.successful_calls.append({
    "agent": agent,
    "tool": tool_name,
    "questionHash": digest,
})

if not evidence.successful_calls:
    raise RuntimeError("Harness Deep Agent 未调用可信业务工具，拒绝无证据回答")
~~~

证据由服务端工具闭包登记，模型不能直接修改。普通查询的 Checkpoint 只保存 Agent、工具和问题哈希等审计元数据；高风险工具等待确认时额外保存恢复所需的问题、工具定义摘要和动作编号。两种情况都不复制可能很大的工具原始结果。

### 6.5 条件抢占待确认动作

~~~java
boolean claimed = chatSessionService.transitionPendingAction(
        sessionId, actionId, "PENDING", "EXECUTING", null);
if (!claimed) {
    throw new BusinessException("待确认动作正在处理或已处理");
}
AgentState result = chatGraph.resume(
        sessionId, Map.of(AgentState.KEY_CONFIRMED, true));
~~~

expectedStatus 条件使并发确认请求只有一个能够进入 EXECUTING。

## 7. 运行守卫

- Deep Agent 最多迭代 40 次，DeepSeek 单次调用最长 60 秒并最多重试一次。
- Deep Agent 不可用、模型异常或迭代超限时，整轮查询进入确定性 LangGraph。
- 确定性流程限制 15 个递归步骤和单节点 4 次访问，防止节点自循环。
- Python 调用 Java 工具最长等待 15 秒，业务工具不做不区分幂等性的自动重试。
- RAG 与 Mem0 可独立降级；写操作始终经过人工确认和数据库条件状态抢占。

## 8. 本地语音输入与中文识别

### 8.1 术语翻译

| 英文术语 | 中文名称 | 本项目中的作用 |
| --- | --- | --- |
| ASR | 自动语音识别 | 将用户说话内容转换为文字 |
| faster-whisper | Whisper 高性能推理实现 | 在本地运行中文语音识别模型 |
| MediaRecorder | 浏览器媒体录制接口 | 获取麦克风音频并生成语音文件 |
| FastAPI | Python 接口框架 | 对外提供本地语音识别接口 |
| VAD | 语音活动检测 | 过滤静音片段，减少无效识别内容 |
| INT8 | 8 位整数计算精度 | 降低 CPU 推理时的内存和计算开销 |
| MIME Type | 媒体类型 | 描述上传音频是 WebM、MP4、OGG 等格式 |
| hotwords | 热词 | 提高“质检、工单追溯、缺陷证据”等领域词汇的识别概率 |
| TTS | 语音合成 | 将文字转换成语音；当前项目尚未实现 |

### 8.2 语音输入框架图

~~~text
用户点击麦克风并开始说话
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤一：浏览器录音                                          │
│                                                              │
│  MediaRecorder 获取麦克风音频                                │
│  自动选择 WebM、MP4 或 OGG 等浏览器支持的格式                │
│  最长录音 60 秒，到达上限后自动停止                          │
└──────────────────────────┬───────────────────────────────────┘
                           │ 上传音频文件
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤二：Spring Boot 接收与安全校验                          │
│                                                              │
│  POST /api/chat-assistant/voice/transcribe                   │
│  校验文件非空、媒体格式和 10 MB 大小上限                     │
│  仅向配置白名单中的语音识别服务转发                          │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤三：本地 faster-whisper 中文识别                        │
│                                                              │
│  FastAPI 服务：127.0.0.1:9001                               │
│  默认模型：base    运行设备：CPU    计算精度：INT8           │
│  启用静音过滤、工业质检提示词和领域热词                      │
└──────────────────────────┬───────────────────────────────────┘
                           │ 返回识别文字
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤四：用户确认                                            │
│                                                              │
│  识别文字只填入输入框，不自动发送                            │
│  用户检查或修改文字后，再手动提交给智能体                    │
└──────────────────────────────────────────────────────────────┘
~~~

### 8.3 为什么采用本地 faster-whisper

| 方案 | 优点 | 局限 | 项目选择 |
| --- | --- | --- | --- |
| 浏览器原生语音识别 | 接入简单 | 浏览器兼容性和服务可用性不可控 | 只使用浏览器录音，不依赖其识别能力 |
| 第三方云语音服务 | 模型成熟、运维成本低 | 音频需要出网，依赖费用和外部服务 | 保留可配置接入能力，不作为默认方案 |
| 本地 faster-whisper | 数据留在本机、可离线、模型可替换 | 需要本地计算资源和独立进程 | 作为当前默认语音识别实现 |

本地服务与 Java 进程分离，避免 Python 原生推理依赖进入 Spring Boot，同时可以独立调整模型大小、运行设备和计算精度。默认采用 `base + CPU + INT8`，优先保证普通开发机器可以运行，并避免与图像检测任务争抢显存。

### 8.4 前端录音核心代码

~~~javascript
mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true })
mediaRecorder = new MediaRecorder(mediaStream, { mimeType })
mediaRecorder.ondataavailable = event => audioChunks.push(event.data)
mediaRecorder.onstop = handleVoiceRecorded
mediaRecorder.start()
~~~

录音结束后，前端把音频片段组合成 `Blob` 文件，调用 `/api/chat-assistant/voice/transcribe`。浏览器会按支持情况依次选择 WebM + Opus、WebM、MP4 或 OGG + Opus，以兼容不同浏览器。

~~~javascript
const audioBlob = new Blob(audioChunks, { type: selectedVoiceMimeType })
const response = await transcribeChatVoice(audioBlob, `voice.${extension}`)
draft.value = response.data?.data?.text || ''
~~~

识别成功后只更新输入框草稿 `draft`，不会直接调用消息发送方法。

### 8.5 本地识别核心代码

~~~python
model = WhisperModel(
    os.getenv("ASR_MODEL", "base"),
    device=os.getenv("ASR_DEVICE", "cpu"),
    compute_type=os.getenv("ASR_COMPUTE_TYPE", "int8"),
)

segments, _ = model.transcribe(
    audio_path,
    language="zh",
    vad_filter=True,
    initial_prompt=INDUSTRIAL_PROMPT,
    hotwords=INDUSTRIAL_HOTWORDS,
)
~~~

`language="zh"` 指定中文识别；`vad_filter=True` 开启语音活动检测；工业提示词与热词用于增强“质检队列、工单追溯、批次追溯、缺陷证据”等项目词汇。

### 8.6 核心配置

| 环境变量 | 默认值 | 中文说明 |
| --- | --- | --- |
| `ASR_MODEL` | `base` | Whisper 模型规格，可按机器性能调整 |
| `ASR_DEVICE` | `cpu` | 模型运行设备 |
| `ASR_COMPUTE_TYPE` | `int8` | 模型计算精度 |
| `ASR_MAX_UPLOAD_BYTES` | `10485760` | Python 服务允许的最大音频大小，默认 10 MB |
| `CHAT_ASSISTANT_VOICE_TRANSCRIBE_URL` | 空，启动脚本配置本机地址 | Spring Boot 转发语音的目标接口 |
| `CHAT_ASSISTANT_VOICE_TRANSCRIBE_ALLOWED_HOSTS` | 本机地址 | 允许访问的语音服务主机白名单 |
| `CHAT_ASSISTANT_VOICE_CONNECT_TIMEOUT_MS` | `1500` | 建立连接的最长等待时间，单位毫秒 |
| `CHAT_ASSISTANT_VOICE_READ_TIMEOUT_MS` | `15000` | 等待识别结果的最长时间，单位毫秒 |
| `CHAT_ASSISTANT_VOICE_MAX_BYTES` | `10485760` | Java 接口允许的最大音频大小，默认 10 MB |

### 8.7 安全性与业务保护

- 后端只接受 HTTP 或 HTTPS 语音服务地址，并校验目标主机白名单，降低服务端请求伪造风险。
- 默认识别服务只监听 `127.0.0.1`，不直接向局域网或公网开放。
- 前后端同时限制录音时长和文件大小，避免超大音频长期占用内存与推理资源。
- 请求结束后删除临时音频文件，减少用户语音在磁盘上的残留时间。
- 识别文本必须由用户确认后才能发送，避免误识别直接触发返工、放行、隔离或报废操作。

### 8.8 当前能力边界

- 当前只有“语音转文字”的自动语音识别功能，没有“文字转语音”的语音合成功能。
- 默认 `base` 模型在准确率和资源消耗之间取平衡，不代表所有口音和嘈杂环境下都能准确识别。
- 热词只能提高领域词汇的识别概率，不能替代用户确认和业务权限校验。
- 本地语音服务不可用时，系统会返回明确错误，用户仍可使用键盘输入，不影响智能助手其他功能。

## 9. Skill 安全下载与隔离

### 9.1 为什么下载后不能直接给 Agent 使用

Skill 本质上是外部输入的指令、参考资料和可选脚本。如果下载完成就自动加载，恶意内容可能诱导 Agent 越权调用工具、泄露上下文或执行不可信代码。因此本项目把“下载”和“激活”拆成两个安全阶段：当前只实现下载、校验、落盘和登记，状态固定为 `QUARANTINED`（隔离待审），不执行其中任何文件，也不分配给主 Agent 或子 Agent。

### 9.2 下载框架图

~~~text
管理员提交 GitHub 仓库、Skill 路径和版本
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤一：Java 管理边界                                      │
│                                                              │
│  登录认证 → 必须具有 ROLE_ADMIN → 参数格式校验              │
│  requestedBy 强制取当前登录用户，不能由浏览器伪造           │
└──────────────────────────┬───────────────────────────────────┘
                           │ HMAC 签名 + 时间戳 + Nonce
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤二：Python 下载边界                                    │
│                                                              │
│  精确仓库允许列表 → 仅 HTTPS → 仅 GitHub API/Codeload 主机  │
│  手动检查每次重定向 → 流式下载并限制压缩包大小              │
└──────────────────────────┬───────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤三：ZIP 与 SKILL.md 安全校验                           │
│                                                              │
│  拒绝 ../ 目录穿越、反斜杠路径、符号链接和控制字符          │
│  限制文件数、单文件大小和解压总量                           │
│  校验 YAML 头部的 name、description 与正文                 │
└──────────────────────────┬───────────────────────────────────┘
                           │ 校验通过
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤四：原子写入隔离区                                     │
│                                                              │
│  临时目录解压 → 计算 SHA-256 → 原子移动 Skill 目录          │
│  原子更新 registry.json → 状态 QUARANTINED                  │
│  任一步失败均清理临时文件；不会加载、执行或分配 Skill       │
└──────────────────────────────────────────────────────────────┘
~~~

### 9.3 Skill 注册字段

当前清单使用持久化 JSON 注册表而不是核心业务数据库，因为隔离 Skill 属于 Python 运行时资产，不参与企业业务数据查询。Docker 部署把该目录挂载到命名卷，容器重建后仍可保留。

| 字段 | 中文含义 | 安全或追踪作用 |
| --- | --- | --- |
| `name` | Skill 名称 | 必须与来源目录同名，只允许小写字母、数字和连字符 |
| `description` | 功能说明 | 从 `SKILL.md` 的 YAML 头部读取 |
| `repository` | 来源仓库 | 必须精确命中管理员配置的允许列表 |
| `source_path` | 仓库内目录 | 只解压这个目录，不落盘整个仓库 |
| `ref` | 分支、标签或提交 | 固定本次下载的代码版本 |
| `checksum` | 内容摘要 | 对全部文件按确定顺序计算 SHA-256，便于审计内容是否变化 |
| `status` | 生命周期状态 | 当前下载结果固定为 `QUARANTINED`，表示尚未审核激活 |
| `installed_at` | 下载时间 | 使用带时区的 UTC 时间记录 |
| `installed_by` | 操作管理员 | 由 Java 登录态写入，不能信任前端传值 |

### 9.4 核心接口与配置

外部管理接口为 `GET /api/chat-assistant/skills` 和 `POST /api/chat-assistant/skills/install`；Python 内部接口使用 POST，是为了复用包含请求体哈希的 HMAC 防重放协议。典型下载请求如下：

~~~json
{
  "repository": "openai/skills",
  "path": "skills/.curated/example-skill",
  "ref": "main"
}
~~~

核心配置包括 `ASSISTANT_SKILLS_ENABLED`、`ASSISTANT_SKILLS_ROOT`、`ASSISTANT_SKILL_ALLOWED_REPOSITORIES`，以及下载大小、文件数量、单文件大小和解压总量四类上限。生产环境应把 `ref` 固定到经过审查的提交哈希，并保持仓库允许列表尽可能短。

### 9.5 当前能力边界

- 已完成：管理员下载、来源约束、压缩包防护、清单校验、内容摘要、原子落盘、隔离清单查询和 Docker 持久化。
- 尚未实现：浏览 Skill 详细内容、人工审核、数字签名验证、激活/停用、版本升级、回滚和向 Agent 分配。
- 即使隔离区包含 `scripts/`，下载链路也不会调用 Shell、导入模块或执行脚本。

## 10. 测试证据

- MySqlCheckpointerTest：检查点序列化、加载和清理。
- AgentOrchestratorServiceImplTest：消息处理、Java/Python 确认恢复、确认成功状态收尾，以及取消后 Checkpoint 清理。
- AgentGraphRunMonitorImplTest：运行指标与守卫。
- 各 AgentServiceImplTest：专业智能体业务边界。
- chat-voice-input-contract.test.cjs：录音状态、格式选择、最长时长和识别结果回填。
- SpeechTranscriptionServiceImplTest：文件校验、服务白名单、响应解析和异常处理。
- test_asr_service.py：健康检查、格式处理、文件大小限制和临时文件清理。
- python_assistant_service/tests/test_graph.py：Python 节点路由、上下文注入、降级、Checkpoint 过滤和异步记忆写入。
- python_assistant_service/tests/test_deep_agent.py：验证主 Agent 工具白名单、四类子 Agent 工具隔离、企业与用户上下文固定传递、写意图旁路、上下文降级、无证据回答拒绝、空工具结果拒绝、高风险工具确认前零调用、配置变化拒绝恢复、确认后单次执行和异常回退。
- python_assistant_service/tests/test_agent_config.py：验证 YAML 职责、Skills、工具与人工介入策略解析、内容变化热加载、新子 Agent 复用受信工具、工具代码白名单、高风险策略约束、重复键、锚点/别名和至少一个启用子 Agent。
- PythonAssistantClientTest：启动本地 HTTP 服务，验证 Java 发出的精确 JSON、HMAC 签名、租户字段以及 Python 响应反序列化契约。
- python_assistant_service/tests/test_security.py：验证签名、防重放、SSE 事件以及 `READY`、`DISABLED`、`MODEL_NOT_CONFIGURED`、`UNSUPPORTED_MODEL` 健康状态。
- python_assistant_service/tests/test_knowledge.py：本地 Markdown 分块与相关性检索。
- python_assistant_service/tests/test_memory.py：Mem0 用户/会话作用域与敏感数据脱敏。
- python_assistant_service/tests/test_skills.py：验证仓库允许列表、重定向主机约束、下载与解压限额、目录穿越、符号链接、清单校验、重复安装、隔离状态和内部接口签名。
- ChatAssistantControllerSkillTest：验证只有管理员可查询和下载 Skill，且操作人只能来自服务端登录态。

## 11. 面试问答

### 为什么 Checkpoint 不直接使用聊天记录代替？

聊天记录只保存用户可见文本，不能表示当前节点、已收集槽位、待确认动作和中间结果。Checkpoint 保存机器执行状态，才能精确恢复中断流程。

### 为什么要拆分多个 Agent？

各业务的数据源、参数和权限不同。专业智能体缩短提示词、限制依赖范围，并能单独测试和扩展。

### 如何避免 Agent 死循环？

Deep Agent 限制最大迭代次数和单次模型调用时间；确定性状态图限制递归步数与单节点访问次数。任一链路超过阈值都会终止当前执行并进入 Fallback，不会继续无限委派。

### 为什么语音识别结果不自动发送？

智能助手可以触发返工、放行、隔离和报废等业务动作。语音存在误识别风险，因此项目只把识别结果填入输入框，要求用户确认后手动发送，把语音识别与业务执行之间增加一道人工安全边界。

### 为什么使用本地 faster-whisper，而不是直接使用浏览器语音识别？

浏览器只负责标准化录音，本地 faster-whisper 负责统一识别。这样不依赖不同浏览器的语音服务实现，音频默认不出本机，还可以配置模型大小、计算设备和工业领域热词。

### 为什么 Skill 下载后不自动生效？

因为 Skill 是外部不可信输入。先校验并隔离，后续再通过人工审核和最小权限分配激活，可以阻断“下载即执行”带来的提示词注入、越权工具调用和恶意脚本风险。
