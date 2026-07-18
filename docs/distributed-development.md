# 微服务分布式开发环境说明

本文说明如何在 `feature/microservices-distributed` 分支启动第一阶段的分布式基础环境和四个 Spring Boot 服务骨架。

> 当前阶段完成的是“可运行的分布式底座”：Nacos 注册与配置、Sentinel 流量保护、Seata 事务协调、Kafka 事件总线、Redis 和按服务拆分的 MySQL 数据库。原有业务仍保留在 `legacy-service`，后续再按领域逐步迁移，避免一次性重写导致功能回归。

## 1. 当前结构

```text
┌──────────────────────────────────────────────────────────────┐
│                    本地开发访问与检查                         │
│   Nacos 控制台 8088   Sentinel 控制台 8858   smoke.ps1       │
└──────────────────────────────┬───────────────────────────────┘
                               │
             ┌─────────────────┴─────────────────┐
             │      Nacos：服务注册与配置管理     │
             └──────┬──────────┬──────────┬──────┘
                    │          │          │
       ┌────────────▼─┐ ┌──────▼───────┐ ┌▼──────────────┐
       │ 认证服务 8101 │ │ 资源服务 8102 │ │ 检测服务 8103 │
       │ auth-service │ │resource-service│ │detection-service│
       └────────────┬─┘ └──────┬───────┘ └┬──────────────┘
                    │          │           │
                    │     ┌────▼───────────▼─┐
                    │     │ 智能助手服务 8104 │
                    │     │ assistant-service │
                    │     └─────────┬────────┘
                    │               │
┌───────────────────▼───────────────▼──────────────────────────┐
│ Sentinel 流量保护 │ Kafka 异步事件 │ Seata 事务协调器 8091    │
├──────────────────────────────────────────────────────────────┤
│ MySQL：每个服务独立数据库 │ Redis：缓存、幂等与短期状态       │
└──────────────────────────────────────────────────────────────┘
```

本阶段遵循项目已经确认的边界：

- 不引入 Spring Cloud Gateway（网关）。
- 不引入 OpenFeign（声明式远程调用）或其他同步 RPC 框架。
- 跨服务异步流程继续使用 Kafka 事件；只有确实需要强一致的数据库事务边界时才使用 Seata。
- Nacos、Sentinel、Seata 均使用阿里巴巴开源体系，不依赖付费云产品。

## 2. 环境要求

- JDK 17。
- Maven 3.9.6，当前机器路径为 `D:\ruanjian\apache-maven-3.9.6`。
- Docker Desktop。
- PowerShell 7（运行冒烟脚本时推荐）。

确认版本：

```powershell
java -version
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -version
docker version
```

如果 `docker compose` 提示没有 Compose 插件，本机可以使用独立程序：

```powershell
& 'D:\ruanjian\Docker\resources\bin\docker-compose.exe' version
```

下文把 Compose 命令写成标准的 `docker compose`。若插件不可用，请替换为上面的完整可执行文件路径，其余参数保持不变。

## 3. 准备本地配置

在项目根目录执行：

```powershell
Copy-Item deploy/distributed/.env.example deploy/distributed/.env
```

`deploy/distributed/.env` 已加入 `.gitignore`，只能保存在本机，不应提交到 Git。

主要变量：

| 变量 | 中文说明 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | 本地 MySQL 根账号密码，仅用于开发环境初始化 |
| `MYSQL_PORT` | MySQL 映射到宿主机的端口，默认 3306 |
| `NACOS_AUTH_TOKEN` | Nacos 签发访问令牌所用的服务端密钥 |
| `NACOS_AUTH_IDENTITY_KEY/VALUE` | Nacos 服务端节点身份标识 |
| `NACOS_USERNAME/PASSWORD` | Nacos 控制台、客户端和 Seata 注册使用的账号密码 |

如果本机 3306 已被已有 MySQL 占用，只修改未提交的 `.env`：

```dotenv
MYSQL_PORT=3307
```

容器内部仍使用 3306，各服务间地址不受影响。

## 4. 启动六个基础组件

```powershell
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml config
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml up -d --build
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml ps
```

应看到以下六个容器均为 `healthy`：

| 组件 | 固定版本 | 端口 | 作用 |
| --- | --- | --- | --- |
| MySQL | 8.4 | 3306（可在 `.env` 改宿主机端口） | 服务独立数据库和 Seata 协调器数据 |
| Nacos | 3.0.3 | 8088、8848、9848 | 控制台、客户端注册/配置、gRPC 通信 |
| Sentinel Dashboard | 1.8.9 | 8858 | 流量、熔断和服务实例观察 |
| Seata Server | 2.5.0 | 8091 | 分布式事务协调器（TC） |
| Kafka | 3.8.1 | 9092 | 跨服务异步事件 |
| Redis | 7.4 | 6379 | 缓存、幂等、计数和短期状态 |

Seata 2.5 的两个注意点：

1. 2.5 已移除旧的内置 Web 控制台，因此没有 7091 页面；8091 是事务协调端口。日志中提到的 NamingServer 控制台不在本项目范围内。
2. 官方 2.5 镜像不内置 MySQL Connector/J。项目的 `deploy/distributed/seata/Dockerfile` 基于官方镜像加入固定版本 9.1.0，并校验 SHA-256，避免运行时才出现“找不到 JDBC 驱动”。

数据库初始化会创建：`door_auth`、`door_resource`、`door_detection`、`door_assistant`、`seata_server`。其中 `seata_server` 包含 `global_table`、`branch_table`、`lock_table`、`distributed_lock` 和 `vgroup_table`。

## 5. 在四个终端启动服务

每个新 PowerShell 终端都先进入项目根目录，并导入本地 `.env`。以下片段不会打印密码：

```powershell
Get-Content deploy/distributed/.env |
    Where-Object { $_ -match '^[A-Z0-9_]+=' } |
    ForEach-Object {
        $name, $value = $_.Split('=', 2)
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
```

然后分别运行：

```powershell
# 终端 1：认证服务，业务端口 8101，Sentinel 通信端口 8719
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl auth-service spring-boot:run
```

```powershell
# 终端 2：资源服务，业务端口 8102，Sentinel 通信端口 8720
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl resource-service spring-boot:run
```

```powershell
# 终端 3：检测服务，业务端口 8103，Sentinel 通信端口 8721
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl detection-service spring-boot:run
```

```powershell
# 终端 4：智能助手服务，业务端口 8104，Sentinel 通信端口 8722
& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' -pl assistant-service spring-boot:run
```

四个 Sentinel 通信端口必须不同，否则多个服务在同一台机器上运行时会争用默认端口 8719。配置中的 `eager=true` 会让 Sentinel 随应用启动并立即向控制台发送心跳，而不是等第一次业务请求后才初始化。

## 6. 控制台和健康地址

| 地址 | 中文说明 |
| --- | --- |
| <http://localhost:8088/> | Nacos 3 控制台，账号密码来自本地 `.env` |
| <http://localhost:8858/> | Sentinel Dashboard，开发镜像默认账号和密码均为 `sentinel` |
| `localhost:8091` | Seata 事务协调端口，不是浏览器页面 |
| <http://localhost:8101/actuator/health> | 认证服务健康检查 |
| <http://localhost:8102/actuator/health> | 资源服务健康检查 |
| <http://localhost:8103/actuator/health> | 检测服务健康检查 |
| <http://localhost:8104/actuator/health> | 智能助手服务健康检查 |

## 7. 一键冒烟检查

四个服务启动后执行：

```powershell
& scripts/distributed/smoke.ps1
```

脚本会检查：

- Nacos 和 Sentinel 控制台可访问。
- Seata 8091 端口可连接。
- 四个 Spring Boot 健康状态都是 `UP`。
- 使用本地 `.env` 中的账号登录 Nacos，并确认四个服务全部注册成功。

任何一项失败都会返回非零退出码，适合本地验收和后续持续集成。

## 8. 停止环境

先在四个服务终端按 `Ctrl+C`，再停止基础组件：

```powershell
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml down
```

普通 `down` 会保留命名数据卷。只有明确要清空所有本地 MySQL、Nacos、Kafka 和 Redis 数据时，才可以额外使用 `down -v`。

## 9. 常见问题

### Nacos 登录或服务注册失败

确认启动服务的终端已经导入 `deploy/distributed/.env`，并检查 Nacos 容器为 `healthy`。服务默认密码 `nacos` 只用于无自定义配置的情况，与本项目 `.env` 中修改后的密码并不相同。

### Sentinel 控制台打开但看不到四个服务

确认 8719～8722 均未被占用，并查看应用日志是否出现 `HeartbeatSender started`。四个服务已经分别配置独立通信端口和提前初始化。

### Seata 反复重启并提示找不到 JDBC 驱动

不要把 Compose 中的定制镜像改回裸 `apache/seata-server:2.5.0.jdk21`。执行带 `--build` 的启动命令，确保项目 Dockerfile 已加入校验过的 MySQL Connector/J。

### 查看容器日志

```powershell
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml logs --tail 100 nacos
docker compose --env-file deploy/distributed/.env -f deploy/distributed/compose.yml logs --tail 100 seata-server
```
