# Nginx 与 Docker Compose 部署代码实现详解

## 一、亮点简历写法

使用 Docker Compose 编排 Vue/Nginx 网关与双 Spring Boot 实例，由 Nginx 承担静态 Web、API 反向代理和 `least_conn` 负载均衡；针对 Hermes Agent SSE 长连接关闭代理缓冲并配置长超时，结合健康检查、非 root 容器和共享上传卷提升部署一致性与可用性。

## 二、整体架构：单入口 + 双后端

```text
浏览器 :80
    │
    ▼
Nginx Container
    ├─ /assets/* ─────────────→ Vue 静态文件（长期缓存）
    ├─ /* ────────────────────→ SPA index.html
    ├─ /api/* ────────────────→ doorhandle_backend
    └─ /messages/stream ──────→ SSE 专用无缓冲代理
                                      │ least_conn
                         ┌────────────┴────────────┐
                         ▼                         ▼
                  backend-1:8080            backend-2:8080
                         │                         │
                         └──── shared uploads ────┘
                                      │
                    host MySQL/Redis/Kafka/Mem0/OSS
```

## 三、代码位置索引

| 模块 | 文件 | 作用 |
| --- | --- | --- |
| 服务编排 | `compose.nginx.yml` | 双后端、网关、网络和卷 |
| Nginx 配置 | `deploy/nginx/nginx.conf` | 静态、代理、负载均衡、SSE |
| 网关镜像 | `deploy/nginx/Dockerfile` | 构建 Vue 并复制到 Nginx |
| 后端镜像 | `deploy/backend/Dockerfile` | Maven/JRE 多阶段构建 |
| 环境模板 | `deploy/docker.env.example` | 外部服务连接和密钥占位 |
| 部署指南 | `docs/nginx-compose-deployment.md` | 启停、验证和排错 |

## 四、详细流程图

### 4.1 镜像构建与启动

```text
Step 1  backend image：Maven 编译 jar
   ↓    JRE Alpine 仅复制 jar，创建 app 用户
Step 2  nginx image：Node npm build 生成 dist
   ↓    Nginx Alpine 复制 dist 和 nginx.conf
Step 3  Compose 启动 backend-1、backend-2
   ↓    curl /actuator/health
Step 4  两个后端健康后启动 Nginx
   ↓
Step 5  宿主机只发布 NGINX_PORT:80
```

### 4.2 API 请求负载均衡

```text
GET /api/detection/tasks
   ↓
Nginx 匹配 location /api/
   ↓
least_conn 比较 backend-1/2 当前连接
   ↓
选择连接较少的实例并传递 Host/X-Real-IP/X-Forwarded-For
   ↓
后端返回响应，浏览器始终只感知 Nginx
```

### 4.3 SSE 请求

```text
POST /api/chat-assistant/messages/stream
   ↓
精确匹配 SSE location（优先于普通 /api/）
   ↓
proxy_buffering off + proxy_cache off
read/send timeout=300s
   ↓
status/chunk/done 事件到达即转发，不在 Nginx 聚合
```

### 4.4 后端故障

```text
backend-1 连续连接失败
   ↓ max_fails=3, fail_timeout=10s
Nginx 暂时摘除 backend-1
   ↓
新请求进入 backend-2
   ↓ 10s 后重新探测 backend-1
```

## 五、配置机制详解

### 5.1 为什么后端不发布宿主端口

Compose 对后端使用 `expose: 8080`，只在内部网络可见；只有 Nginx 使用 `ports`。这保证认证、跨域、安全头和代理规则均经过统一入口。

### 5.2 外部依赖

MySQL、Redis、Kafka 和 Mem0 复用宿主机服务，通过 `host.docker.internal` 访问。该方案适合本地和单机部署；生产集群应使用稳定 DNS 或独立基础设施地址。

## 六、代码详解

### 6.1 Compose 复用后端配置

文件：`compose.nginx.yml`

```yaml
x-backend-common: &backend-common
  build:
    context: .
    dockerfile: deploy/backend/Dockerfile
  env_file:
    - ${DOORHANDLE_ENV_FILE:-deploy/docker.env.example}
  expose:
    - "8080"
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]

services:
  backend-1:
    <<: *backend-common
  backend-2:
    <<: *backend-common
```

逐句解释：

1. YAML Anchor 让两个实例共享镜像、环境和健康检查，避免配置漂移。
2. 环境文件可由变量替换，默认示例文件不含真实密钥。
3. `expose` 仅声明容器网络端口，不直接暴露宿主机。
4. Nginx 的 `depends_on` 等待健康状态，而不只是等待进程创建。

### 6.2 least_conn 上游

文件：`deploy/nginx/nginx.conf`

```nginx
upstream doorhandle_backend {
    least_conn;
    server backend-1:8080 max_fails=3 fail_timeout=10s;
    server backend-2:8080 max_fails=3 fail_timeout=10s;
    keepalive 32;
}
```

逐句解释：

1. `least_conn` 优先选择活跃连接更少的实例，适合 Agent 长请求。
2. 三次失败后将节点暂时标记不可用，十秒后重试。
3. `keepalive 32` 复用 Nginx 到后端的连接，减少 TCP 建连成本。

### 6.3 普通 API 代理头

```nginx
location /api/ {
    proxy_pass http://doorhandle_backend;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

逐句解释：保留原始 Host、客户端 IP 和协议，后端日志、审计及绝对地址生成才不会只看到 Nginx 容器地址。

### 6.4 SSE 无缓冲代理

```nginx
location = /api/chat-assistant/messages/stream {
    proxy_pass http://doorhandle_backend;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
}
```

逐句解释：

1. 精确匹配确保该规则不会被普通 API location 覆盖。
2. 关闭 buffering/cache，使每个事件即时到达浏览器。
3. 300 秒与 Java `SseEmitter` 超时保持一致。

### 6.5 非 root 后端镜像

文件：`deploy/backend/Dockerfile`

```dockerfile
FROM maven:3.9.9-eclipse-temurin-17 AS build
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app \
    && mkdir -p /app/uploads /app/logs \
    && chown -R app:app /app
COPY --from=build --chown=app:app /workspace/target/*.jar app.jar
USER app
```

逐句解释：

1. 构建阶段包含 Maven，运行阶段只保留 JRE 和 jar。
2. 显式创建上传和日志目录并修改所有权。
3. `COPY --chown` 防止 jar 归 root 所有。
4. `USER app` 降低容器被利用后的权限范围。

## 七、配置汇总

| 配置 | 当前值 | 作用 |
| --- | --- | --- |
| Nginx 算法 | `least_conn` | 平衡长短请求 |
| 后端实例 | 2 | 基础冗余 |
| Upstream keepalive | 32 | 复用连接 |
| 失败阈值 | 3/10s | 临时摘除故障节点 |
| SSE 超时 | 300s | 支持长回答 |
| 静态缓存 | 1 year/immutable | 缓存带 hash 资源 |

## 八、关键设计总结

| 特性 | 实现方式 | 代码位置 |
| --- | --- | --- |
| Web 服务器 | Nginx 托管 Vue dist | Nginx Dockerfile |
| 反向代理 | `/api/` → upstream | `nginx.conf` |
| 负载均衡 | `least_conn` 双实例 | upstream |
| 流式兼容 | SSE 关闭缓冲和缓存 | 精确 location |
| 启动保护 | Actuator 健康检查 | Compose |
| 容器安全 | 多阶段 + 非 root | Backend Dockerfile |
| 配置隔离 | env example + Git ignore | deploy env |

## 九、验证边界与优化

- Compose 配置已通过静态解析，Java、Python 和前端测试已通过。
- 当时 Docker Engine 未运行，因此真实容器启动和端到端冒烟测试需在 Engine 启动后执行。
- 生产环境还可增加 HTTPS、WAF/限流、Prometheus、集中日志、资源配额和 Kubernetes 滚动发布。
- 双实例下 JVM 本地限流和缓存应升级为 Redis 等共享实现。

## 十、面试问题与答案

### 1. Nginx 在项目中做了什么？

托管 Vue、反代 API、负载均衡双后端，并为 Agent SSE 提供无缓冲长连接代理。

### 2. 为什么使用 least_conn？

Agent 和报告请求耗时不同，轮询只按数量分配；least_conn 更能反映实例当前压力。

### 3. 两个实例如何共享状态？

业务和 Checkpoint 在 MySQL，共享缓存可在 Redis，任务在 Kafka，图片在 OSS/共享卷，实例本身尽量无状态。

### 4. SSE 为什么要关闭 buffering？

否则 Nginx 会积累多个 chunk 后再发送，用户看不到真正的流式效果。

### 5. `depends_on` 是否等于应用可用？

普通启动顺序不等于可用；本项目使用 `condition: service_healthy` 配合 Actuator 健康检查。

### 6. 当前方案是高可用集群吗？

它提供应用层双实例冗余，但单机 Nginx 和 Docker Host 仍是单点；生产级高可用还需要多节点编排和外部负载均衡。

