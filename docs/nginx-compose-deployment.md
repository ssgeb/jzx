# Nginx + Docker Compose 部署指南

该方案启动一个 Nginx 网关、两个 Spring Boot 实例和一个 Python Harness Agent。Nginx 托管 Vue 静态资源，将 `/api` 请求按最少连接数分发至两个后端，并为智能助手 SSE 接口关闭代理缓冲。Python 容器运行 Deep Agents，通过 Nginx 的容器内专用 `8081` 端口回调 Java 只读工具。MySQL、Redis、Kafka、Mem0、OSS 和 DeepSeek 使用外部服务。

## 1. 前置条件

- Docker Desktop 已启动；
- Docker Compose 可用，优先使用 `docker compose`；也兼容独立的 `docker-compose` 命令；
- 外部 MySQL、Redis、Kafka 已启动，并允许 Docker 容器访问；
- 如启用 Mem0，确保其服务地址可由容器访问；
- OSS、DeepSeek 和 JWT 使用独立的生产凭据。

Windows 和 macOS 使用 `host.docker.internal` 访问宿主机。Compose 已为 Linux 添加 `host-gateway` 映射，因此可以使用同一配置。

## 2. 准备环境变量

根目录 `.env.example` 面向本地开发；本部署方案使用 `deploy/docker.env.example` 生成容器专用配置，并由 `DOORHANDLE_ENV_FILE` 明确指定。容器配置只负责覆盖数据库、Redis、Kafka、外部服务、凭据和 JVM 参数，不应复制 `application.yml` 中无需变更的公共默认值。

PowerShell：

```powershell
Copy-Item deploy/docker.env.example deploy/docker.env
notepad deploy/docker.env
$env:DOORHANDLE_ENV_FILE = 'deploy/docker.env'
```

Linux：

```bash
cp deploy/docker.env.example deploy/docker.env
vi deploy/docker.env
export DOORHANDLE_ENV_FILE=deploy/docker.env
```

必须替换数据库密码、JWT Secret、助手 HMAC Secret、DeepSeek Key 和 OSS 凭据。`ASSISTANT_DEEP_AGENT_MODEL` 应保持为支持工具调用的 `deepseek-chat`；它与 Java 使用的 `DEEPSEEK_MODEL` 相互独立。模型不兼容时，Harness Deep Agent 会进入确定性降级流程。根目录 `.env` 与 `deploy/docker.env` 均不得提交；仓库中只保留不含真实凭据的 `.example` 模板。

如果暂时没有 Mem0，可设置：

```dotenv
MEM0_ENABLED=false
```

## 3. 构建并启动

```powershell
docker compose -f compose.nginx.yml up -d --build
```

如果系统使用独立 Compose，可将后续命令中的 `docker compose` 替换为：

```powershell
docker-compose -f compose.nginx.yml up -d --build
```

默认访问地址为 `http://localhost`。如需修改端口：

```powershell
$env:NGINX_PORT = '8088'
docker compose -f compose.nginx.yml up -d --build
```

Linux 可使用：

```bash
NGINX_PORT=8088 docker compose -f compose.nginx.yml up -d --build
```

## 4. 检查运行状态

```powershell
docker compose -f compose.nginx.yml ps
docker compose -f compose.nginx.yml logs --tail=100 nginx
docker compose -f compose.nginx.yml logs --tail=100 backend-1 backend-2
docker compose -f compose.nginx.yml logs --tail=100 python-assistant
```

四个容器均应处于 running/healthy 状态。

验证 Nginx Web 服务：

```powershell
curl.exe http://localhost/nginx-health
curl.exe -I http://localhost/
```

验证 API 反向代理：

```powershell
curl.exe -i http://localhost/api/auth/check
```

未携带登录 Cookie 时，认证检查会返回未登录结果；能够收到 Spring Boot 的 JSON 响应即说明反向代理生效。默认 Nginx 仅代理 `/api/`，后端 Actuator 不对公网暴露。后端健康状态应通过 `docker compose ps` 或容器内健康检查确认。

验证 Python Harness Agent 的容器内健康状态：

```powershell
docker compose -f compose.nginx.yml exec python-assistant python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8090/internal/v1/health').read().decode())"
```

返回结果中的 `deep_agent_configured` 为 `true`，表示 DeepSeek、Deep Agents 和 Harness 配置均已生效。`deep_agent_status` 会进一步说明配置状态：`READY` 表示可用，`DISABLED` 表示被主动关闭，`MODEL_NOT_CONFIGURED` 表示模型开关或密钥缺失，`UNSUPPORTED_MODEL` 表示模型不支持当前 Harness 工具调用。健康接口不会返回 API Key 或 HMAC Secret。

Python 的 `8090`、Nginx 内部工具入口的 `8081` 都只在 Compose 网络中开放，没有映射到宿主机。Python 调用 `http://nginx:8081/internal/v1/agent-tools/**` 时仍需 HMAC 签名，Nginx 再按最少连接数转发到两个 Java 实例。

## 5. 验证负载均衡与故障切换

连续调用同一个业务 API，并同时观察两个后端日志：

```powershell
docker compose -f compose.nginx.yml logs -f backend-1 backend-2
```

在另一个终端临时停止一个实例：

```powershell
docker compose -f compose.nginx.yml stop backend-1
```

业务请求应继续由 `backend-2` 处理。恢复实例：

```powershell
docker compose -f compose.nginx.yml start backend-1
```

## 6. 验证 Harness Agent 与智能助手 SSE

登录系统后发送智能助手消息，浏览器 Network 面板中的 `/api/chat-assistant/messages/stream` 应持续接收 `connected`、`status`、`chunk` 和 `done` 事件，而不是等待回答全部生成后一次返回。

容器配置默认使用 `CHAT_ASSISTANT_ENGINE=python`。查询请求由 Deep Agent 规划并委派给检测、资源、报表或运维子 Agent；写请求、模型不可用或 Deep Agent 异常时进入确定性 LangGraph，Java 侧仍保留 `CHAT_ASSISTANT_FALLBACK_TO_JAVA=true` 作为跨进程最终兜底。

Nginx 已为该接口配置：

- `proxy_buffering off`；
- `proxy_cache off`；
- 300 秒读写超时；
- `X-Accel-Buffering: no`。

## 7. 更新、停止与回滚

重新构建并滚动启动：

```powershell
docker compose -f compose.nginx.yml up -d --build
```

停止服务但保留运行数据卷：

```powershell
docker compose -f compose.nginx.yml down
```

查看镜像并回滚到之前的 Git 提交：

```powershell
git switch --detach <previous-commit>
docker compose -f compose.nginx.yml up -d --build
```

不要使用 `down -v`，除非明确需要删除共享运行目录卷。

## 8. 当前开发机说明

当前开发机可以通过独立的 `docker-compose v5.1.1` 解析配置，但 `docker compose` 子命令不可用，且 Docker Engine 未启动。仓库契约测试和 `docker-compose config` 已覆盖静态验证；实际镜像构建和容器运行需要先启动 Docker Desktop。
