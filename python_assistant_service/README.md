# Python 智能体服务

该服务使用 FastAPI 和 Deep Agents 承担 Harness 智能体的任务规划、上下文管理和专业子 Agent 委派，底层仍由 LangGraph 执行。浏览器仍访问 Java `/api/chat-assistant/**`，本服务只接受 Java 的签名内部请求。

## 本地启动

在项目根目录执行：

```powershell
conda env create -f python_assistant_service/environment.yml
$env:ASSISTANT_HMAC_SECRET="请替换为至少32位随机字符串"
$env:JAVA_AGENT_TOOL_BASE_URL="http://127.0.0.1:8080"
$env:MEM0_SERVICE_URL="http://127.0.0.1:8081"
$env:DEEPSEEK_ENABLED="true"
$env:DEEPSEEK_API_KEY="请替换为真实密钥"
$env:ASSISTANT_DEEP_AGENT_MODEL="deepseek-chat"
conda run -n doorhandlecatch-assistant python -m uvicorn python_assistant_service.app.main:app --host 127.0.0.1 --port 8090
```

健康检查：`GET http://127.0.0.1:8090/internal/v1/health`。

Python 会直接加载系统手册 Markdown 执行本地 RAG，并按 `tenant_user_id + session_id` 调用 Mem0 服务检索和异步写入长期记忆。RAG 或 Mem0 不可用时只降级上下文增强，不阻断业务查询。

Harness 主 Agent 只看到 `write_todos` 和 `task`；检测、资源、报表和运维四个子 Agent 各自只拥有一个固定的 Java 只读工具。Deep Agents 默认的文件读写、Shell 执行和通用子 Agent 均已禁用。写操作不交给大模型直接执行，仍使用 Java 人工确认和 CAS 状态转移。

## YAML 子智能体弹性加载

子智能体不再硬编码在 `deep_agent.py`，而是从 `python_assistant_service/config/subagents.yaml` 加载。每个子智能体独立声明：

- `id`、`name`、是否启用和中文职责说明；
- `responsibilities`：业务责任与禁止越界的范围；
- `skills`：引用 YAML 中受版本控制的可信业务 Skill 指令；
- `tools`：只允许引用 Python `TRUSTED_TOOL_BINDINGS` 已注册的只读工具。

加载器会根据文件内容摘要检测变化，下一次请求自动使用新配置，无需重启 Python 服务。YAML 使用 Pydantic 严格校验，拒绝未知字段、重复键、锚点、别名、未定义 Skill、未注册工具、伪造工具绑定以及所有子智能体均禁用的配置。配置无效时健康状态显示 `SUBAGENT_CONFIG_INVALID`，查询回到确定性 LangGraph。

主 Agent 不直接获得这些 Java 业务工具。加载器只把 `enabled: true` 的子智能体连接到 Deep Agents，Harness 再通过 `task` 工具进行委派。修改 YAML 可以调整职责、Skill 指令、启停状态和已有工具组合；新增一种真实工具仍需先修改代码白名单并完成审查。

Docker Compose 将该 YAML 以只读文件挂载到 `/app/config/subagents.yaml`。修改宿主机配置后，容器内下一次请求即可重新加载；只读挂载同时防止 Agent 或容器进程反向修改配置。

这里的 YAML Skill 是随项目发布的可信业务指令，不等于通过网络下载、仍处于 `QUARANTINED` 的外部 Skill。隔离 Skill 不会被 YAML 名称引用自动激活。

业务回答必须至少包含一次成功、非空的 Java 工具调用证据；模型绕过工具直接回答、工具异常或返回空内容时，质量门会拒绝该结果并降级到确定性 LangGraph。Checkpoint 只记录 Agent、工具名和问题摘要哈希，不保存完整工具响应。

Deep Agent 当前通过独立的 `ASSISTANT_DEEP_AGENT_MODEL` 使用具备工具调用能力的 `deepseek-chat`，不会覆盖 Java 或确定性路由使用的 `DEEPSEEK_MODEL`。Python 不直接访问核心业务表；Deep Agent 未配置、处理写请求或执行异常时，自动降级到原有确定性 LangGraph。

## Skill 安全下载

系统支持管理员从明确允许的 GitHub 公共仓库下载 Skill。浏览器调用 Java 管理接口，Java 校验 `ROLE_ADMIN` 并使用 HMAC 调用 Python；Python 再执行仓库允许列表、HTTPS 主机、压缩包大小、文件数量、目录穿越、符号链接和 `SKILL.md` 清单校验。

下载成功的状态固定为 `QUARANTINED`（隔离待审），只写入 Skill 持久化目录和 `registry.json`。下载阶段不会导入 Python 模块、执行脚本、加载提示词或分配给 Agent。当前版本也没有启用 FastMCP。

管理员接口：

- `GET /api/chat-assistant/skills`：查询隔离区 Skill 清单。
- `POST /api/chat-assistant/skills/install`：下载并校验 Skill，请求字段为 `repository`、`path` 和 `ref`。

默认仅允许 `openai/skills`。本地可通过 `ASSISTANT_SKILL_ALLOWED_REPOSITORIES` 配置英文逗号分隔的精确仓库列表；Docker Compose 使用命名卷 `assistant-skills` 保存下载结果。
