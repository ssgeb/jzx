# Nginx Docker Compose Deployment Design

## Goal

为 DoorHandleCatch 增加可复现的生产部署入口：使用 Docker Compose 启动一个 Nginx 网关和两个 Spring Boot 实例。Nginx 同时承担 Vue 静态资源 Web 服务、API 反向代理和后端负载均衡；MySQL、Redis、Kafka、OSS、Mem0 等依赖继续使用宿主机或外部服务。

## Scope

本次包含：

- 多阶段构建 Vue 静态资源并制作 Nginx 镜像；
- 多阶段构建 Spring Boot JAR 并制作统一后端镜像；
- Compose 启动 `backend-1`、`backend-2` 和 `nginx`；
- Nginx 使用 `least_conn` 将 API 请求分发到两个后端；
- 为智能助手 SSE、SPA 路由、静态缓存、Gzip、健康检查和真实客户端 IP 提供专门配置；
- 提供外部 MySQL、Redis、Kafka、Mem0 和密钥配置示例；
- 提供配置契约测试和部署文档。

本次不容器化 MySQL、Redis、Kafka、Python Worker、Mem0 或 OSS，也不实现 Kubernetes、自动扩缩容和 TLS 证书自动签发。

## Architecture

```text
Browser
   |
   v
Nginx container :80
   |-- / and /assets/* -----------------> Vue dist
   |-- /api/chat-assistant/messages/stream -> backend upstream (SSE, no buffering)
   `-- /api/* --------------------------> backend upstream
                                              |-- backend-1:8080
                                              `-- backend-2:8080
                                                    |-- external MySQL
                                                    |-- external Redis
                                                    |-- external Kafka
                                                    `-- external Mem0 / OSS / DeepSeek
```

Nginx 与两个后端处于 Compose 私有网络中。只有 Nginx 暴露宿主机端口；后端通过 `expose` 向容器网络开放 8080，避免绕过网关直接访问。

## Components

### Backend image

`deploy/backend/Dockerfile` 使用 Maven + JDK 17 构建项目，再将 JAR 复制到 JRE 17 运行镜像。镜像以非 root 用户运行，通过 `JAVA_OPTS` 接收 JVM 参数，并使用 `/actuator/health` 作为容器健康检查。

两个后端服务复用同一镜像和同一组外部依赖环境变量。应用产生的运行文件使用命名卷或共享卷挂载，确保请求被不同实例处理时仍能访问一致的数据目录；OSS 业务文件继续存放在外部 OSS。

### Nginx image

`deploy/nginx/Dockerfile` 先在 Node 阶段执行 `npm ci` 和 `npm run build`，再将 `frontend/dist` 复制到固定的 Nginx Web 根目录。镜像复制项目专用的 `nginx.conf`，不依赖宿主机预先执行前端构建。

### Compose orchestration

根目录 `compose.nginx.yml` 定义：

- `backend-1`：Spring Boot 实例一；
- `backend-2`：Spring Boot 实例二；
- `nginx`：依赖两个健康后端后启动，对外暴露 `${NGINX_PORT:-80}:80`；
- `doorhandle-network`：三服务共享的 bridge 网络；
- 必要的共享运行目录卷。

`deploy/docker.env.example` 提供外部服务连接参数和密钥占位符，不包含真实凭据。Windows 与 macOS 可使用 `host.docker.internal` 访问宿主机服务；Linux 部署通过 `extra_hosts: host-gateway` 获得同名地址。

## Nginx Routing

### Load balancing

`upstream doorhandle_backend` 使用 `least_conn`，包含：

- `backend-1:8080 max_fails=3 fail_timeout=10s`；
- `backend-2:8080 max_fails=3 fail_timeout=10s`；
- keepalive 连接池。

当某实例连续失败时，开源 Nginx 在 `fail_timeout` 窗口内临时停止向其分发请求。

### Static Web server

- `/assets/`：长缓存并设置 immutable；
- `/`：`try_files $uri $uri/ /index.html`，支持 Vue History/Hash 路由与刷新回退；
- 开启 Gzip，并仅压缩文本、JavaScript、JSON、SVG 等类型；
- 添加基础安全响应头。

### API reverse proxy

通用 `/api/` 请求转发至 `doorhandle_backend`，传递 `Host`、`X-Real-IP`、`X-Forwarded-For`、`X-Forwarded-Proto`，并使用 HTTP/1.1 与 upstream keepalive。

### SSE streaming

`/api/chat-assistant/messages/stream` 使用更长的读取超时，关闭 `proxy_buffering`、`proxy_cache` 和响应缓冲，避免智能助手流式 token 被 Nginx 聚合后一次性返回。

## Failure Handling

- 后端容器健康检查失败时，Compose 标记实例为 unhealthy；
- Nginx 被动健康检查在请求失败后临时跳过异常实例；
- 两个后端均不可用时，Nginx 返回 502，不伪造业务成功响应；
- Nginx 容器和后端容器使用 `restart: unless-stopped`；
- 外部依赖不可达时由 Spring Boot 健康端点和应用日志暴露原因。

## Security

- 仅 Nginx 对宿主机暴露端口；
- Compose 示例文件只保留变量名和安全占位符；
- JWT、数据库密码、DeepSeek API Key 和 OSS 凭据通过未提交的环境文件注入；
- Nginx 限制请求体大小，避免无限制上传；
- TLS 终止预留在 Nginx 层，但首版不提交证书或私钥。

## Testing

新增 Node 契约测试读取 Compose、Dockerfile 和 Nginx 配置，验证：

- 存在两个后端服务和一个 Nginx 服务；
- 后端不直接发布宿主机端口；
- upstream 同时包含两个后端并使用 `least_conn`；
- `/api/` 反向代理到 upstream；
- SSE 路由关闭 buffering；
- Vue 使用 SPA fallback；
- 静态资源启用缓存；
- 配置示例不包含真实密钥。

如果本机存在可用的 Docker Compose 和已启动的 Docker Engine，再执行配置解析和容器级冒烟测试。当前机器的独立 `docker-compose v5.1.1` 可以解析配置，但 `docker compose` 子命令不可用且 Docker Engine 未启动，因此仓库级契约测试与 `docker-compose config` 是本轮验证边界，容器运行验证需在 Docker Desktop 启动后执行。

## Acceptance Criteria

1. 一条 Compose 命令可以构建并声明 Nginx 与两个后端实例；
2. 浏览器只通过 Nginx 访问前端和 `/api`；
3. API 请求配置为在两个后端间按最少连接数分发；
4. 智能助手 SSE 可以持续流式传输，不被代理缓冲；
5. 外部依赖和敏感信息均通过环境变量注入；
6. 配置契约测试、现有前后端测试和前端生产构建保持通过。
