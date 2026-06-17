# Redis 缓存与 key 设计

## 目标

为 DoorHandleCatch 增加可选 Redis 缓存基础设施，支持后续缓存仪表盘、模型元数据、设备状态、检测任务进度、聊天会话等热点读数据。

默认不强依赖 Redis 服务。本地开发和测试继续使用 Spring `simple` cache；生产环境可通过环境变量启用 Redis。

## 启用方式

```bash
APP_REDIS_ENABLED=true
SPRING_CACHE_TYPE=redis
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DATABASE=0
REDIS_HEALTH_ENABLED=true
```

## 数据结构选择

- `String`：缓存接口响应、计数器、短生命周期状态值，例如检测任务进度快照。
- `Hash`：设备状态、模型元数据、任务摘要等字段会独立更新的对象。
- `List`：最近 N 条操作日志、最近检测任务。
- `Set`：在线设备集合、活跃采集员集合、任务标签集合。
- `Sorted Set`：按时间排序的最近任务、模型使用排行、设备活跃排行。
- `Stream`：后续如需持久事件流，可承载检测任务状态变更、设备心跳事件。

## key 命名

统一使用小写、冒号分隔，根前缀为 `doorhandlecatch`：

```text
doorhandlecatch:detection:task:{taskId}
doorhandlecatch:detection:task:{taskId}:progress
doorhandlecatch:device:{deviceCode}:status
doorhandlecatch:model:{modelId}:metadata
doorhandlecatch:chat:session:{sessionId}
doorhandlecatch:rate-limit:{scope}:{identityDigest}
doorhandlecatch:lock:{resource}:{id}
doorhandlecatch:stream:{name}
```

代码中通过 `RedisKeys` 生成，避免散落硬编码。

## 缓存 TTL

默认 TTL 为 30 分钟，按缓存域覆盖：

- `dashboard`: 5 分钟
- `model`: 10 分钟
- `device`: 5 分钟
- `employee`: 10 分钟
- `detection-task`: 2 分钟
- `chat-session`: 15 分钟

## 已接入缓存路径

- 仪表盘：总览统计、检测趋势、分布统计、地区统计、模型统计，缓存域 `dashboard`。
- 模型管理：模型元数据详情，缓存域 `model`；模型新增、更新、评估、灰度配置、删除后清理模型和仪表盘缓存。
- 设备管理：设备详情、全部设备、未绑定设备、设备统计，缓存域 `device`；设备新增、更新、删除、解绑、心跳后清理设备和仪表盘缓存。
- 人员管理：人员详情、人员类型列表、设备绑定人员、人员统计，缓存域 `employee`；人员新增、更新、删除、绑定/解绑设备后清理人员、设备和仪表盘缓存。
- 检测任务：任务进度、任务分页列表，缓存域 `detection-task`；任务创建、上传确认、流转、复核、完成、失败后清理任务和仪表盘缓存。

检测结果详情和追溯详情暂不缓存，因为接口会生成 OSS 临时签名 URL。缓存这类响应容易返回过期链接，后续如需优化可拆分为“任务元数据缓存 + 每次实时签名 URL”。

## 运行安全

- 默认 `APP_REDIS_ENABLED=false`，避免无 Redis 环境启动失败。
- 默认 `REDIS_HEALTH_ENABLED=false`，避免未部署 Redis 时 Actuator health 变为 DOWN。
- Redis 缓存不存放密码、JWT、OSS Secret 等敏感明文。
- 对用户输入、URL、长路径等不直接作为 key，使用摘要或短业务 ID。
- Redis JSON 序列化复用 Spring Boot `ObjectMapper`，保证 `LocalDateTime` 等 Java 时间字段能稳定序列化。

## 后续接入建议

- 对设备实时在线状态可进一步使用 `Hash` 或 `Set` 建模，例如 `doorhandlecatch:device:online`。
- 对模型使用排行、设备活跃排行可进一步使用 `Sorted Set` 建模。
- 对检测任务状态变更事件可进一步使用 `Stream` 建模，支撑审计、通知和异步消费。
- 对热点列表缓存如需更精细，可从 `allEntries=true` 演进为按业务 ID 精准失效。
