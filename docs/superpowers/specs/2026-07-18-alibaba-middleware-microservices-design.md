# 基于阿里巴巴中间件的微服务分布式改造设计

## 1. 目标

在 `feature/microservices-distributed` 分支中，将现有 Spring Boot 单体应用渐进式拆分为四个业务服务，并落地阿里巴巴开源中间件提供的三类分布式能力：

- Nacos：服务注册、服务发现和配置中心。
- Sentinel：接口限流、资源保护、熔断和降级。
- Seata：真实多 MySQL 数据源场景下的分布式事务。

本方案明确不使用 Spring Cloud Gateway、不使用 OpenFeign，也不依赖阿里云 MSE、RDS、ACK 等付费托管产品。服务间业务协作以 Kafka 事件为主，避免构造不必要的同步调用链。

## 2. 核心原则

1. 每个服务拥有自己的数据库和业务边界。
2. 服务之间不共享 Entity、Mapper 和数据库账号。
3. 不使用同步 RPC 读取其他服务数据库，跨服务状态通过 Kafka 事件同步。
4. Kafka、OSS 和外部 AI 调用不纳入 Seata 事务。
5. Seata 只用于确实需要同时修改多个 MySQL 数据源的业务，不为了展示技术制造错误边界。
6. Sentinel 的写操作降级只能返回失败，不能伪造成功。
7. 前端直接访问各业务服务，服务分别配置 CORS、JWT 校验和统一错误结构。

## 3. 技术版本

| 组件 | 版本 |
|---|---|
| Java | 17 |
| Spring Boot | 3.5.0 |
| Spring Cloud | 2025.0.0 |
| Spring Cloud Alibaba | 2025.0.0.0 |
| Nacos | 3.0.3 |
| Sentinel | 1.8.9 |
| Seata | 2.5.0 |
| MyBatis-Plus | 3.5.17 |

版本由父 POM 和 BOM 集中管理。升级依赖后必须先让现有单体测试通过，再开始移动业务代码。

## 4. 服务边界

### 4.1 auth-service

- 端口：`8101`
- 数据库：`door_auth`
- 负责企业租户、用户、员工、角色权限、登录和 JWT 签发。
- 对外路径：`/api/auth/**`、`/api/users/**`、`/api/employees/**`。

### 4.2 resource-service

- 端口：`8102`
- 数据库：`door_resource`
- 负责模型、模型操作记录、设备、设备使用记录、设备告警和模型文件。
- 对外路径：`/api/models/**`、`/api/devices/**`、`/api/files/**`。

### 4.3 detection-service

- 端口：`8103`
- 数据库：`door_detection`
- 负责检测任务、检测结果、质量处置、统计、Dashboard、Kafka 检测调度和 OSS 图片链路。
- 对外路径：`/api/detection/**`、`/api/statistics/**`、`/api/dashboard/**`。

### 4.4 assistant-service

- 端口：`8104`
- 数据库：`door_assistant`
- 负责主 Agent、专业子 Agent、聊天会话、待确认操作、Checkpoint、RAG、Mem0 和语音转写适配。
- 对外路径：`/api/chat/**`。

### 4.5 外部运行组件

- Python ONNX Worker：消费 Kafka 检测任务。
- Kafka：传递检测任务、资源快照、用户权限和业务结果事件。
- OSS：保存检测原图、预览图和结果 JSON。
- Redis：缓存、限流辅助和短期状态。
- Mem0、Chroma、DeepSeek：由 assistant-service 使用。

## 5. 前端访问方式

不使用统一网关，Vue 根据环境变量访问四个服务：

```text
VITE_AUTH_API_BASE=http://localhost:8101
VITE_RESOURCE_API_BASE=http://localhost:8102
VITE_DETECTION_API_BASE=http://localhost:8103
VITE_ASSISTANT_API_BASE=http://localhost:8104
```

每个服务必须：

- 允许配置中的前端 Origin，禁止无条件 `*` 与凭据同时使用。
- 独立校验 JWT、租户和权限。
- 返回相同的 `ApiResponse` 和错误码结构。
- 生成或透传 `X-Request-Id`。

生产部署可在四个服务前使用普通 Nginx 做 TLS 终止和静态反向代理，但 Nginx 不承担微服务注册发现、服务治理或业务编排。

## 6. Maven 多模块结构

```text
DoorHandleCatch/
├── pom.xml
├── legacy-service/          迁移期间保留，完成后删除
├── platform-common/
├── platform-security/
├── event-contracts/
├── auth-service/
├── resource-service/
├── detection-service/
└── assistant-service/
```

### 6.1 platform-common

只包含统一响应、错误码、异常基类、请求链路常量和无业务含义的工具类。

### 6.2 platform-security

包含 JWT 校验、租户主体、租户上下文、安全过滤器和权限辅助类。JWT 使用 RS256：auth-service 持有私钥，其他服务只持有验证公钥。

### 6.3 event-contracts

只包含 Kafka 事件 DTO、事件类型、版本和序列化契约。禁止放置数据库 Entity、Mapper 或业务 Service。

## 7. 数据所有权

### 7.1 door_auth

- `enterprise`
- `users`
- `employee`
- 角色权限及认证审计表

### 7.2 door_resource

- `model_management`
- `model_operation_log`
- `device_management`
- `device_usage_record`
- `device_capture_alert`

### 7.3 door_detection

- `detection_task`
- 检测结果和质量处置表
- `detection_outbox_event`
- `detection_consumed_event`

### 7.4 door_assistant

- `chat_session`
- `chat_message`
- `chat_pending_action`
- Agent Checkpoint 和审计表

每个参与 Seata AT 事务的数据库创建 `undo_log`；Seata Server 使用独立数据库 `seata_server`。

## 8. Nacos 设计

### 8.1 职责

- 四个业务服务和 Seata Server 注册到 Nacos。
- 保存各服务可动态调整的非敏感配置。
- 保存 Sentinel 动态规则。
- 按开发、测试和生产命名空间隔离。

### 8.2 配置文件

Spring Cloud Alibaba 2025.x 使用 `spring.config.import`：

```yaml
spring:
  config:
    import:
      - optional:nacos:${spring.application.name}.yaml?refreshEnabled=true
```

Data ID：

```text
auth-service.yaml
resource-service.yaml
detection-service.yaml
assistant-service.yaml
sentinel-auth-rules.json
sentinel-resource-rules.json
sentinel-detection-rules.json
sentinel-assistant-rules.json
```

数据库密码、JWT 私钥、OSS 密钥和 AI 密钥通过环境变量或容器 Secret 注入，不保存到 Git 和普通 Nacos 明文配置。

## 9. Sentinel 设计

### 9.1 保护对象

- auth-service：登录、验证码和令牌刷新接口。
- resource-service：大文件上传、模型校验和设备写操作。
- detection-service：任务创建、批量查询和统计接口。
- assistant-service：聊天、语音转写和 Agent 执行。

### 9.2 规则

- 登录按 IP 和账号限流。
- 检测任务创建按租户限流。
- Agent 对话按用户与租户限流。
- 大文件上传限制并发线程数。
- 外部 OSS、DeepSeek、Mem0、Chroma 调用使用慢调用比例熔断。

### 9.3 降级边界

- 只读查询可以返回 `degraded=true` 的缓存或“暂不可用”结果。
- 写操作被限流或依赖熔断时返回 HTTP 429/503。
- 禁止写操作在未执行时返回成功。
- Sentinel 规则由 Nacos 数据源动态推送。

## 10. 无同步 RPC 的服务协作

服务之间通过 Kafka 事件传递必要数据快照：

```text
auth.user.changed
resource.model.changed
resource.device.changed
detection.task.created
detection.task.progress
detection.task.finished
detection.statistics.updated
```

示例：

```text
resource-service 修改模型
    -> 发布 resource.model.changed
    -> detection-service 更新本地 model_snapshot
    -> assistant-service 更新可查询的资源摘要
```

事件要求：

- `eventId`：全局事件唯一标识。
- `eventType`：事件类型。
- `eventVersion`：契约版本。
- `tenantId`：企业租户。
- `aggregateId`：业务实体 ID。
- `occurredAt`：发生时间。
- `payload`：不可变业务快照。

消费者以 `eventId` 唯一索引去重；乱序事件通过版本号或更新时间拒绝旧数据覆盖新数据。

## 11. Kafka 与本地事务

采用 Outbox 保证数据库写入和待发送事件原子提交：

```text
本地事务
    ├──修改业务表
    └──写 outbox_event
提交成功
    -> Publisher 发送 Kafka
    -> 成功标记 SENT
    -> 失败限次重试
```

Outbox 状态：`NEW`、`SENDING`、`SENT`、`FAILED`。重试使用有上限的指数退避，超过上限进入人工处理状态。

## 12. Seata 设计与限制

### 12.1 接入

- 部署 Seata Server 2.5.0。
- Seata Server 注册到 Nacos，并使用 MySQL 保存全局事务状态。
- 需要 AT 模式的业务数据源启用自动代理并创建 `undo_log`。
- 全局事务入口使用 `@GlobalTransactional`。

### 12.2 可以使用的场景

只有单个业务用例确实需要同时操作多个 MySQL 数据源时才使用 Seata，例如迁移工具在切库期间原子写入源数据库的迁移标记和目标数据库的接收记录。

### 12.3 不使用的场景

- Kafka 消息发送和消费。
- OSS 文件上传和删除。
- Python Worker 推理。
- Mem0、Chroma 和 DeepSeek HTTP 请求。
- 单个服务自己的普通数据库事务。

### 12.4 重要边界

在不使用 OpenFeign、Dubbo、RestClient 等服务间调用的前提下，Seata 不承担“跨服务调用链事务”。项目会完成真实可运行的 Seata Server、客户端、数据源代理和多数据源集成测试，但不会让一个业务服务长期直接访问另一个服务的数据库。

## 13. 故障恢复

- Nacos 暂时不可用：已运行实例使用本地缓存，启动失败时给出明确诊断。
- Sentinel 触发：返回统一限流或服务不可用响应。
- Kafka 发送失败：Outbox 保留事件并限次重投。
- Kafka 重复投递：消费者按 `eventId` 幂等。
- Seata 分支失败：通过 `undo_log` 回滚 MySQL 修改。
- Seata Server 不可用：拒绝开启新的全局事务，不降级为无事务写入。
- OSS 临时文件残留：记录孤儿对象并由清理任务处理。
- Agent 中断：assistant-service 从 Checkpoint 恢复。

## 14. 渐进迁移顺序

### 阶段 0：版本和测试基线

- 同步 main 到分布式分支。
- 升级 Spring Boot、Spring Cloud Alibaba 和 MyBatis-Plus。
- 修复兼容问题并通过现有测试。

### 阶段 1：Maven 骨架和中间件

- 建立父 POM、公共模块、事件契约和四个服务骨架。
- 部署 Nacos、Sentinel Dashboard、Seata Server、MySQL、Kafka 和 Redis。
- 验证四个服务注册、配置加载和健康检查。

### 阶段 2：auth-service

- 迁移用户、员工、租户、登录和 JWT。
- 发布用户与租户变更事件。

### 阶段 3：resource-service

- 迁移模型、设备、使用记录和告警。
- 发布模型、设备变更事件。

### 阶段 4：detection-service

- 迁移检测任务、结果、统计、Kafka 和 OSS。
- 建立本地模型/设备快照和 Outbox。

### 阶段 5：assistant-service

- 迁移 Agent、聊天、RAG、Mem0 和 Checkpoint。
- 使用本地事件快照替代跨服务数据库查询。

### 阶段 6：收口

- 前端配置四个服务地址。
- 删除 Legacy 模块和重复代码。
- 更新启动脚本、README 和亮点文档。

## 15. 验收标准

- 四个服务均能独立构建和启动。
- 四个服务与 Seata Server 出现在 Nacos 中。
- Nacos 配置修改能够按设计动态生效。
- Sentinel 限流、慢调用熔断和写操作失败响应测试通过。
- Kafka 事件重复、乱序和失败重投测试通过。
- Seata 多数据源提交和回滚集成测试通过。
- 每个业务服务只持有自己的数据库账号。
- 前端可直接调用四个服务并完成核心流程。
- 多租户越权测试通过。
- Maven、前端和关键 Python 测试通过。

## 16. 非目标

- 不实现统一 API Gateway。
- 不使用 OpenFeign 或其他同步服务间 RPC。
- 不使用阿里云付费托管产品。
- 不使用 Seata 包裹 Kafka、OSS 或外部 HTTP 调用。
- 不把 Python Worker 重写为 Java。

## 17. 官方依据

- Spring Cloud Alibaba 版本关系：<https://sca.aliyun.com/docs/2025.x/overview/version-explain/>
- Nacos 配置与注册：<https://sca.aliyun.com/docs/2025.x/user-guide/nacos/quick-start/>
- Sentinel 快速开始：<https://sca.aliyun.com/docs/2025.x/user-guide/sentinel/quick-start/>
- Seata AT 模式：<https://seata.apache.org/docs/user/mode/at/>
- Seata Docker Compose 部署：<https://seata.apache.org/docs/v2.5/ops/deploy-by-docker-compose-142/>

