# 工业缺陷检测平台微服务分布式改造设计

## 1. 目标

在 `feature/microservices-distributed` 分支中，将现有 Spring Boot 单体应用渐进式改造为五个可独立启动、注册、配置和部署的服务，并完整落地以下分布式能力：

- Nacos：服务注册、服务发现和配置中心。
- Spring Cloud Gateway：统一 API 入口和网关治理。
- OpenFeign：服务间同步 RPC。
- Seata：MySQL 跨服务写操作的分布式事务。
- Sentinel：网关与服务调用的限流、熔断和降级。

改造必须保留现有多租户安全边界、Kafka 检测任务链路、OSS 文件链路、Agent 会话恢复和 Vue 前端主要功能。

## 2. 设计原则

1. **渐进迁移**：每个阶段均可编译、测试和启动，不进行一次性大爆炸式重写。
2. **数据库归服务所有**：服务只能访问自己的业务数据库，跨服务访问必须通过 API 或事件。
3. **同步与异步分工**：需要立即结果的短调用使用 OpenFeign；检测推理等耗时任务继续使用 Kafka。
4. **事务边界明确**：Seata 只覆盖支持 ACID 本地事务的 MySQL 写操作，不宣称能够回滚 Kafka、OSS 或外部 AI API。
5. **失败必须显式**：查询可以返回标记明确的降级结果，写操作不得通过伪造成功进行降级。
6. **租户身份不可伪造**：业务服务从经过验证的 JWT 建立租户上下文，不只信任客户端请求头。
7. **契约优先**：Feign DTO 独立于数据库实体，避免共享实体造成隐式数据库耦合。

## 3. 版本基线

采用 Spring Cloud Alibaba 官方版本矩阵中对应关系明确的组合：

| 组件 | 版本 |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.0 |
| Spring Cloud | 2025.0.0 |
| Spring Cloud Alibaba | 2025.0.0.0 |
| Nacos | 3.0.3 |
| Seata | 2.5.0 |
| Sentinel | 1.8.9 |

升级先在单体回归测试通过后进行，再开始移动业务代码。所有版本由父 POM 的 BOM 集中管理，子模块不得单独覆盖 Spring Cloud 生态组件版本。

## 4. 服务边界

### 4.1 gateway-service

- 端口：`8080`
- 无业务数据库。
- 负责统一路由、跨域、请求编号、JWT 前置校验、网关 Sentinel 规则和统一错误响应。
- 不编排业务事务，不直接访问 Mapper。

### 4.2 auth-service

- 端口：`8101`
- 数据库：`door_auth`
- 负责企业租户、用户、员工、角色权限、登录和 JWT 签发。
- JWT 至少包含 `userId`、`tenantId`、`username`、角色、签发时间和过期时间。

### 4.3 resource-service

- 端口：`8102`
- 数据库：`door_resource`
- 负责模型、模型操作日志、设备、设备使用记录、设备告警和模型资源文件。
- 向 detection-service 和 assistant-service 提供只读查询及受保护的资源使用登记 API。

### 4.4 detection-service

- 端口：`8103`
- 数据库：`door_detection`
- 负责检测任务、检测结果、质量处置、统计和 Dashboard 聚合。
- 负责 Kafka 检测任务创建/完成事件和 OSS 检测图片链路。
- 通过 OpenFeign 校验模型、设备并登记资源使用记录。

### 4.5 assistant-service

- 端口：`8104`
- 数据库：`door_assistant`
- 负责主 Agent、专业子 Agent、聊天会话、待确认操作、Checkpoint、RAG、Mem0 和语音转写适配。
- 通过 Feign 查询检测、模型和设备数据，不直接读取其他服务数据库。

### 4.6 保留的外部组件

- Python ONNX Worker：继续消费 Kafka 检测消息。
- Kafka：继续承担耗时检测任务和结果事件。
- OSS：继续存储原图、预览图和结果 JSON。
- Mem0、Chroma、DeepSeek：继续作为 assistant-service 的外部依赖。

## 5. Maven 多模块结构

```text
DoorHandleCatch/
├── pom.xml
├── platform-common/
├── platform-security/
├── service-contracts/
├── gateway-service/
├── auth-service/
├── resource-service/
├── detection-service/
└── assistant-service/
```

### 5.1 platform-common

仅包含统一响应、错误码、异常基类、请求链路常量和无业务含义的工具类。不包含 Controller、Entity、Mapper 和具体业务服务。

### 5.2 platform-security

包含 JWT 解析、租户主体、租户上下文、服务安全过滤器及权限辅助类。它只提供认证能力，不依赖任何业务数据库。

### 5.3 service-contracts

按提供方划分包：

```text
contracts.auth
contracts.resource
contracts.detection
```

仅包含 Feign 接口、请求/响应 DTO、公共业务枚举和稳定错误契约。禁止放置 MyBatis Entity 或 Mapper。

## 6. 数据所有权

### 6.1 door_auth

- `enterprise` 或现有租户主体表。
- `users`
- `employee`
- 角色、权限和用户角色关联表。
- 认证安全审计记录。

### 6.2 door_resource

- `model_management`
- `model_operation_log`
- `device_management`
- `device_usage_record`
- `device_capture_alert`
- 资源服务业务审计记录。

### 6.3 door_detection

- `detection_task`
- 检测结果和质量处置相关表。
- `detection_outbox_event`：待发布 Kafka 事件。
- `detection_consumed_event`：消费幂等记录。
- 检测服务业务审计记录。

### 6.4 door_assistant

- `chat_session`
- `chat_message`
- `chat_pending_action`
- Agent Checkpoint 状态。
- 智能助手业务审计记录。

### 6.5 Seata 数据

- 独立数据库 `seata_server` 保存全局事务、分支事务和锁信息。
- 每个参与 AT 事务的业务数据库都创建 `undo_log`。

开发环境允许四个业务数据库共用一个 MySQL 实例，但账号权限必须限制到对应数据库。生产环境可以迁移到独立 MySQL 实例而不改变服务代码。

跨服务只保存对方业务 ID，不建立跨数据库外键。

## 7. Nacos 设计

### 7.1 环境隔离

使用独立命名空间：

```text
doorhandle-dev
doorhandle-test
doorhandle-prod
```

### 7.2 配置划分

```text
gateway-service.yaml
auth-service.yaml
resource-service.yaml
detection-service.yaml
assistant-service.yaml
common-observability.yaml
sentinel-gateway-rules.json
sentinel-service-rules.json
```

服务启动所必需的最小配置保留在本地 `application.yml`；可动态调整的业务、限流和连接配置放入 Nacos。

数据库密码、JWT 签名密钥、OSS 密钥和大模型密钥不得提交到 Git。开发环境从 `.env` 注入，生产环境从容器 Secret 或密钥管理服务注入。

Nacos 开发环境使用带鉴权的 Docker 单机模式；生产设计为集群模式、持久化数据库、鉴权和内部网络访问，不暴露公网。

## 8. Gateway 设计

路由前缀保持前端语义稳定：

```text
/api/auth/**        -> auth-service
/api/users/**       -> auth-service
/api/employees/**   -> auth-service
/api/models/**      -> resource-service
/api/devices/**     -> resource-service
/api/files/**       -> resource-service
/api/detection/**   -> detection-service
/api/statistics/**  -> detection-service
/api/dashboard/**   -> detection-service
/api/chat/**        -> assistant-service
```

Gateway 负责：

- 生成或透传 `X-Request-Id`。
- 对公开登录接口以外的请求执行 JWT 前置验证。
- 保留 `Authorization` 并转发到下游。
- 通过 Nacos 服务发现使用 `lb://service-name` 路由。
- 将下游不可用、限流和超时统一映射为稳定错误结构。

下游服务仍需独立校验 JWT 和权限，不能把 Gateway 当作唯一安全边界。

## 9. OpenFeign 设计

同步调用关系：

```text
assistant-service -> detection-service：查询任务、结果和统计
assistant-service -> resource-service：查询模型和设备
detection-service -> resource-service：校验模型设备、登记使用记录
```

Feign 请求拦截器统一传播：

- `Authorization`
- `X-Request-Id`
- Seata XID（由集成组件传播）

租户和用户身份最终以服务内验证后的 JWT Claims 为准。内部接口同时校验调用方身份，避免用户绕过 Gateway 直接调用内部写接口。

Feign DTO 使用版本稳定的字段，不返回数据库 Entity。所有超时必须显式配置，禁止无限等待。

## 10. Seata 设计

### 10.1 事务场景

首个全局事务位于 detection-service 的检测任务创建用例：

```text
@GlobalTransactional
创建 detection_task
    -> Feign 调用 resource-service
       -> 校验模型与设备
       -> 写入 device_usage_record
```

任一 MySQL 分支失败时，detection 和 resource 两个数据库通过 AT 模式统一回滚。

### 10.2 使用边界

- 只覆盖 MySQL 数据修改。
- Kafka 发送不加入 Seata 事务。
- OSS 上传和删除不加入 Seata 事务。
- 外部模型、Mem0、Chroma、DeepSeek 调用不加入 Seata 事务。
- 查询接口不创建全局事务。

检测任务事务提交后，由本地 Outbox 记录负责可靠发布 Kafka 消息；消息携带 `eventId` 和 `dispatchId`，消费者通过唯一约束幂等。

### 10.3 失败处理

- Feign 写调用超时或返回失败：抛出异常并触发全局回滚。
- 全局事务回滚失败：保留 Seata 事务记录并告警，不向用户返回成功。
- OSS 已产生的临时对象：记录孤儿对象并由清理任务处理。

## 11. Sentinel 设计

### 11.1 Gateway 规则

- 登录：按 IP 和账号限制请求频率。
- 文件上传：按用户限制并发。
- 检测任务创建：按租户限制请求频率。
- Agent 对话：按用户与租户限制请求频率。
- 所有路由设置突发流量保护。

### 11.2 Feign 与服务规则

- Detection 和 Resource 查询使用慢调用比例熔断。
- Assistant 查询依赖提供带 `degraded=true` 标记的只读降级结果。
- 所有写操作的 Fallback 必须抛出服务不可用异常并返回 HTTP 503，禁止返回伪造成功。
- Sentinel 规则从 Nacos 动态加载，并按环境命名空间隔离。

## 12. Kafka 最终一致性

检测推理保持异步：

```text
detection-service
    -> 本地事务写 detection_task + detection_outbox_event
    -> Outbox Publisher 发送 detection.task.created
    -> Python Worker 推理
    -> 发送 detection.task.finished
    -> detection-service 幂等更新结果
```

可靠性要求：

- `eventId` 唯一标识事件，用于消费去重。
- `dispatchId` 标识一次任务派发，防止旧结果覆盖新派发。
- Outbox 状态包含 `NEW`、`SENDING`、`SENT`、`FAILED`。
- 发送失败采用有上限的指数退避。
- 超过重试上限进入死信/人工处理状态，不无限循环。

## 13. 多租户与安全

- 每个业务表保留或补齐 `tenant_id`。
- 用户级隐私数据同时带 `user_id`。
- JWT 使用 RS256：auth-service 只持有签名私钥，Gateway 和业务服务只持有验证公钥；密钥通过 `kid` 支持轮换。
- Assistant 的会话、记忆、待确认操作按用户隔离；检测、模型、设备和质量数据按企业共享。
- Feign 内部写接口执行租户、权限和调用方三重校验。
- 日志不得记录 JWT、数据库密码、OSS 密钥和完整隐私内容。

## 14. 可观测性

每个服务暴露 Actuator 健康检查和指标：

- Nacos 注册状态。
- Feign 调用耗时、错误率和熔断状态。
- Seata 全局事务提交/回滚数量。
- Kafka Outbox 积压和失败数量。
- Sentinel 限流次数。
- Agent 执行次数、失败原因和 Checkpoint 保存失败次数。

日志统一包含 `requestId`、`tenantId`、`userId`、`serviceName`；敏感字段脱敏。

## 15. 渐进迁移阶段

### 阶段 0：基线同步和依赖升级

- 将 main 快进同步到分布式分支。
- 升级 Spring Boot/Cloud/Alibaba BOM。
- 运行现有 Java、前端和 Python 测试，建立兼容基线。

### 阶段 1：Maven 骨架和基础设施

- 建立父 POM、公共模块和五个服务模块。
- 增加开发用 Docker Compose：Nacos、MySQL、Seata、Sentinel Dashboard。
- 增加数据库初始化和健康检查。

### 阶段 2：Gateway 与 Legacy 过渡

- Gateway 接入 Nacos、动态路由和 Sentinel。
- 现有单体作为临时 Legacy 服务注册到 Nacos。
- 前端先统一访问 Gateway。

### 阶段 3：auth-service

- 迁移用户、员工、租户、登录和 JWT。
- 数据迁移并验证旧账号可登录。
- Gateway 将认证相关路由切到 auth-service。

### 阶段 4：resource-service

- 迁移模型、设备、使用记录和告警。
- 建立 Resource Feign 契约。
- 切换资源相关路由。

### 阶段 5：detection-service

- 迁移检测任务、结果、统计、Kafka 和 OSS。
- 接入 Resource Feign、Seata 和 Outbox。
- 验证跨库回滚、重复事件和重新派发。

### 阶段 6：assistant-service

- 迁移 Agent、聊天、RAG、Mem0 和 Checkpoint。
- 使用 Feign 替换对 Detection/Resource Mapper 的直接依赖。
- 保持用户级会话隔离和企业级业务数据共享。

### 阶段 7：收口

- 删除 Legacy 路由和重复代码。
- 完成数据一致性校验。
- 更新启动脚本、README 和项目亮点文档。
- 执行端到端验收。

## 16. 测试策略

### 16.1 单元测试

- DTO 和错误契约。
- JWT/租户上下文。
- Feign Fallback。
- Outbox 状态转换与幂等逻辑。
- Agent 调用适配。

### 16.2 模块集成测试

- Gateway 路由、JWT 和 Sentinel 限流。
- Nacos 配置加载和服务注册。
- MyBatis Mapper 只访问本服务数据库。
- Feign 请求上下文传播。
- Seata 正常提交与异常回滚。

### 16.3 端到端测试

```text
登录
 -> 查询模型和设备
 -> 创建检测任务
 -> Kafka 调度 Worker
 -> 查看结果和统计
 -> Agent 查询检测结果
 -> 验证限流、熔断、回滚和租户隔离
```

### 16.4 验收条件

- 五个服务均能独立构建和启动。
- 所有服务出现在 Nacos 注册列表中。
- 前端只访问 Gateway。
- 各业务服务只持有自己的数据库凭据。
- OpenFeign 正常路径、超时和降级均有测试。
- Seata 跨 detection/resource 回滚测试通过。
- Sentinel 动态规则生效，写操作不会降级为假成功。
- Kafka 重复消息不会重复更新任务。
- 多租户越权测试通过。
- Maven 全量测试、前端测试和关键 Python 测试通过。

## 17. 非目标

- 本轮不引入 Kubernetes、Service Mesh 或 Dubbo。
- 本轮不把 Python Worker 重写为 Java。
- 本轮不使用 Seata 包裹 Kafka、OSS 或第三方 HTTP 调用。
- 本轮不拆出独立报表、模型、设备等更多微服务。
- 本轮不改变核心检测模型和算法。

## 18. 官方依据

- Spring Cloud Alibaba 2025.x 版本矩阵：<https://sca.aliyun.com/docs/2025.x/overview/version-explain/>
- Spring Cloud 与 Spring Boot 版本兼容说明：<https://spring.io/projects/spring-cloud/>
- Nacos Docker 部署与生产安全提示：<https://nacos.io/en/docs/latest/quickstart/quick-start-docker/>
- Seata AT 模式和 `undo_log` 原理：<https://seata.apache.org/docs/user/mode/at/>
- Sentinel 与 OpenFeign 集成：<https://sca.aliyun.com/docs/2023/user-guide/sentinel/advanced-guide/>
