# Python 智能体服务

该服务使用 FastAPI 和 LangGraph 承担智能体路由与编排。浏览器仍访问 Java `/api/chat-assistant/**`，本服务只接受 Java 的签名内部请求。

## 本地启动

在项目根目录执行：

```powershell
conda env create -f python_assistant_service/environment.yml
$env:ASSISTANT_HMAC_SECRET="请替换为至少32位随机字符串"
$env:JAVA_AGENT_TOOL_BASE_URL="http://127.0.0.1:8080"
$env:MEM0_SERVICE_URL="http://127.0.0.1:8081"
conda run -n doorhandlecatch-assistant python -m uvicorn python_assistant_service.app.main:app --host 127.0.0.1 --port 8090
```

健康检查：`GET http://127.0.0.1:8090/internal/v1/health`。

Python 会直接加载系统手册 Markdown 执行本地 RAG，并按 `tenant_user_id + session_id` 调用 Mem0 服务检索和异步写入长期记忆。RAG 或 Mem0 不可用时只降级上下文增强，不阻断业务查询。

没有引入 Skill 或 FastMCP。所有业务查询和写操作均通过 Java 的固定内部工具接口完成，Python 不直接访问核心业务表。
