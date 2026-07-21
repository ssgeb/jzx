# Nginx、Docker Compose 与双实例部署

> 返回总览：[项目亮点与面试指南](../项目亮点与面试指南.md)

## 1. 部署目标

统一前端和 API 入口，使用 Nginx 托管 Vue 静态资源并代理两个 Spring Boot 实例，同时运行 Python Harness Deep Agent，并针对 SSE 长连接配置关闭缓冲和长超时。

### 1.1 术语与配置翻译

| 英文术语或配置 | 中文名称 | 在本项目中的作用 |
| --- | --- | --- |
| Nginx | Web 服务器与反向代理 | 托管前端文件并把接口请求转发给后端实例 |
| Docker Compose | 多容器编排工具 | 统一定义并启动前端、网关、双后端和 Python Harness Agent |
| Vue dist | Vue 构建产物目录 | 保存浏览器可以直接访问的页面和静态资源 |
| API | 应用程序接口 | 前端访问 Spring Boot 业务能力的入口 |
| SSE | 服务器发送事件 | 后端向浏览器持续推送智能助手的流式回答 |
| upstream | 上游服务组 | Nginx 中由多个后端实例组成的转发目标 |
| least_conn | 最少连接负载均衡 | 优先把新请求交给当前连接数较少的实例 |
| backend-1 / backend-2 | 后端实例一 / 后端实例二 | 两个共同对外提供服务的 Spring Boot 容器 |
| python-assistant | Python 智能体容器 | 运行 Deep Agents 主 Agent、专业子 Agent 和确定性降级流程 |
| Nginx 8081 | 容器内工具入口 | 只在 Compose 网络中负载均衡 Python 到 Java 的签名回调 |
| expose | 容器内部暴露端口 | 只允许容器网络访问，不直接开放给宿主机 |
| ports | 宿主机端口映射 | 将容器端口直接暴露到宿主机；后端没有使用 |
| health check | 健康检查 | 判断容器是否能够正常接收请求 |
| JAR | Java 可执行归档包 | Spring Boot 后端最终运行的软件包 |
| JRE | Java 运行环境 | 运行 JAR 所需的精简环境 |

## 2. 部署框架图

~~~text
浏览器访问系统（HTTP :80）
        │
        ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤一：Nginx 统一入口与路由                                │
│                                                              │
│  /、/assets  → Vue 构建后的静态资源                          │
│  /api        → Spring Boot 上游服务组（最少连接策略）        │
│  SSE 流式接口 → 关闭代理缓冲，保持持续推送                   │
└──────────────────────────┬───────────────────────────────────┘
                           │
                      请求属于哪类？
                           │
                 ┌─────────┴─────────┐
                 │                   │
              前端资源            业务接口/流式接口
                 │                   │
                 ▼                   ▼
        ┌────────────────┐   ┌───────────────────────────────┐
        │ Vue 构建产物   │   │ 步骤二：后端双实例负载均衡    │
        │ 页面入口文件   │   │                               │
        │ 静态资源目录   │   │ 后端实例一 :8080              │
        └────────────────┘   │ 后端实例二 :8080              │
                             │ Nginx 按最少连接数分发请求     │
                             └──────────────┬────────────────┘
                                            │
                                            ▼
                  智能助手请求？
                    ┌─────┴─────┐
                    │           │
                   否           是
                    │           │ Java 签名请求
                    │           ▼
                    │  ┌──────────────────────────────────────┐
                    │  │ 步骤三：Python Harness Deep Agent    │
                    │  │ 查询规划与子 Agent 委派              │
                    │  │ 写操作/异常 → 确定性流程             │
                    │  └──────────────────┬───────────────────┘
                    │                     │ HMAC 只读工具回调
                    │                     ▼
                    │  Nginx :8081（仅容器网络）→ 双 Java 实例
                    └─────────────┬────────────────────────────
                                  ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤四：共享外部状态                                        │
│  MySQL：业务与检查点    Redis：缓存/防重放    Mem0：长期记忆  │
│  Kafka：异步检测消息    OSS：图片与检测结果                   │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
┌──────────────────────────────────────────────────────────────┐
│  步骤五：Docker Compose 容器运行保障                         │
│                                                              │
│  健康检查 + 启动顺序 + 环境变量配置 + 容器重启策略           │
└──────────────────────────────────────────────────────────────┘
~~~

## 3. 镜像构建

### 3.1 后端

~~~mermaid
flowchart LR
    SRC[Spring Boot 源码] --> MAVEN[Maven 3.9 + JDK 17 构建]
    MAVEN --> JAR[可执行 JAR]
    JAR --> JRE[JRE Alpine 运行镜像]
    JRE --> APP[普通 app 用户运行]
~~~

构建镜像包含 Maven，运行镜像只包含 JRE、应用 JAR 和健康检查工具，减少运行时体积和攻击面。

### 3.2 前端

Node 阶段执行 npm ci 和 npm run build，再把 dist 复制到 Nginx 镜像。

### 3.3 Python Harness Agent

使用 Python 3.11 安装固定版本的 Deep Agents 与 LangChain DeepSeek 集成，以非特权用户运行 FastAPI，并由容器健康检查访问 `/internal/v1/health`。Python 代码不连接 Java 业务数据库，业务查询只能调用 HMAC 签名的 Java 内部工具。

## 4. 核心部署字段与 Compose 服务

| 服务 | 端口 | 作用 |
| --- | --- | --- |
| nginx | 宿主机 80 | 唯一外部入口 |
| backend-1 | 容器 8080 | 第一后端实例 |
| backend-2 | 容器 8080 | 第二后端实例 |
| python-assistant | 容器 8090 | Harness Agent，仅容器网络可访问 |
| nginx 内部监听 | 容器 8081 | Python 回调 Java 工具的负载均衡入口，不映射宿主机 |
| doorhandle-runtime | 命名卷 | 两实例共享上传目录 |

后端、Python Agent 和 Nginx `8081` 使用 `expose` 而不是 `ports`，不直接暴露到宿主机。

## 5. 核心代码与配置

### 5.1 least_conn

~~~nginx
upstream doorhandle_backend {
    least_conn;
    server backend-1:8080 max_fails=3 fail_timeout=10s;
    server backend-2:8080 max_fails=3 fail_timeout=10s;
    keepalive 32;
}
~~~

Agent、报表和普通查询耗时差异明显，least_conn 比只看请求序号的轮询更适合该场景。

### 5.2 Vue History 路由

~~~nginx
location / {
    try_files $uri $uri/ /index.html;
}
~~~

找不到真实静态文件时回退到 index.html，由 Vue Router 处理前端路由。

### 5.3 SSE

~~~nginx
location = /api/chat-assistant/messages/stream {
    proxy_pass http://doorhandle_backend;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
    add_header X-Accel-Buffering no;
}
~~~

如果不关闭 buffering，Nginx 可能聚合多个 status/chunk 事件，用户会长时间看不到输出。

## 6. 双实例共享状态

| 状态 | 实际存储 |
| --- | --- |
| 业务数据 | MySQL |
| 智能体检查点（Agent Checkpoint） | MySQL 的 chat_session.state_json 字段 |
| 聊天消息 | MySQL |
| 上传文件 | OSS 或共享卷 |
| Spring Cache | 默认本地 simple，可显式启用 Redis |
| Kafka 消费进度 | Kafka Consumer Group |

RedisCacheManager 只有在 app.redis.enabled=true 时启用，因此不能把 Redis 描述为默认的会话存储。

## 7. 健康检查与启动顺序

- 后端镜像访问 /actuator/health。
- Python 镜像访问 /internal/v1/health，并报告 `deep_agent_configured`。
- Compose 的 Nginx depends_on 等待两个后端 service_healthy。
- Python Agent 等待 Nginx 健康后启动；Java 在 Python 尚未就绪时可使用原有回退链路。
- Nginx 上游设置 max_fails 和 fail_timeout。
- Redis 健康检查默认可关闭，避免可选缓存阻断应用启动。

## 8. 安全设计

- Java 服务使用普通 app 用户。
- 运行镜像不包含 Maven 工具链。
- 敏感配置由环境变量注入。
- 后端不直接暴露宿主机端口。
- Python `8090` 和内部工具代理 `8081` 不映射宿主机端口。
- Python 到 Java 的工具请求仍校验 HMAC、时间窗、Nonce、会话所有者和租户用户。
- Nginx 添加 nosniff、SAMEORIGIN 和 Referrer-Policy 响应头。

## 9. 当前限制

~~~mermaid
flowchart LR
    SINGLE[Nginx 单实例] --> SPOF[入口单点]
    HOST[单机 Compose] --> HOSTFAIL[主机故障不可用]
    EXTERNAL[外部 MySQL/Kafka/Redis] --> DEP[依赖其自身高可用]
~~~

双后端实例提升进程级可用性，但不是跨主机高可用。生产环境还需要：

- Nginx 或负载均衡器冗余。
- MySQL 主从或集群。
- Kafka 部署多个消息代理节点（Broker）。
- Redis Sentinel/Cluster。
- Prometheus、日志集中采集和分布式追踪。
- Kubernetes 滚动发布与自动扩缩容。

## 10. 测试与面试问答

主要验证：frontend/tests/nginx-compose-deployment-contract.test.cjs、docker compose config、后端健康检查和 SSE 手工验证。

### SSE 经过负载均衡会不会切换实例？

一条已建立的 TCP 连接会持续绑定到选中的后端，不会在传输中途切换。新请求才会重新执行 least_conn 选择。

### 双实例为什么不一定需要粘性会话？

认证使用 JWT，聊天记录和 Checkpoint 存在 MySQL，实例尽量无状态。但若启用本地缓存或内存限流，它们仍不共享，需要 Redis 化。
