"""FastAPI 入口。"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request
from fastapi.responses import StreamingResponse

from .clients import DeepSeekIntentModel, JavaToolClient
from .deep_agent import HarnessDeepAgent
from .graph import AgentGraph
from .knowledge import LocalKnowledgeBase
from .memory import MemoryServiceClient
from .schemas import AgentInvokeRequest, AgentResponse, AgentResumeRequest, HealthResponse
from .security import ReplayGuard, build_replay_guard, verify_internal_request
from .service import AgentService
from .settings import Settings


def _sse(event: str, data: dict) -> str:
    payload = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event}\ndata: {payload}\n\n"


def create_app(
    settings: Settings | None = None,
    service: AgentService | None = None,
    replay_guard: ReplayGuard | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    replay_guard = replay_guard or build_replay_guard(settings)
    owned_clients: list[object] = []
    knowledge = None
    deep_agent = None
    if service is None:
        tool_client = JavaToolClient(settings)
        model = DeepSeekIntentModel(settings)
        memory = MemoryServiceClient(settings)
        knowledge = LocalKnowledgeBase(settings)
        deep_agent = HarnessDeepAgent(settings, tool_client, knowledge, memory)
        service = AgentService(
            AgentGraph(settings, tool_client, model, knowledge, memory),
            memory,
            deep_agent,
        )
        owned_clients.extend((tool_client, model, memory))

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        shutdown = getattr(service, "shutdown", None)
        if shutdown is not None:
            await shutdown()
        for client in owned_clients:
            close = getattr(client, "aclose", None)
            if close is not None:
                await close()

    app = FastAPI(
        title="DoorHandleCatch Python 智能体服务",
        version="0.1.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )

    async def require_signature(request: Request) -> None:
        await verify_internal_request(request, settings, replay_guard)

    @app.get("/internal/v1/health", response_model=HealthResponse)
    async def health() -> HealthResponse:
        return HealthResponse(
            status="UP",
            service=settings.service_name,
            graph_version=settings.graph_version,
            signature_required=settings.require_signature,
            java_tools_configured=bool(settings.java_tool_base_url),
            model_configured=bool(settings.deepseek_enabled and settings.deepseek_api_key),
            deep_agent_configured=bool(deep_agent is not None and deep_agent.available),
            rag_chunks=knowledge.chunk_count if knowledge is not None else 0,
            memory_configured=settings.memory_enabled and bool(settings.memory_service_url),
        )

    @app.post(
        "/internal/v1/agent/invoke",
        response_model=AgentResponse,
        dependencies=[Depends(require_signature)],
    )
    async def invoke(request: AgentInvokeRequest) -> AgentResponse:
        return await service.invoke(request)

    @app.post(
        "/internal/v1/agent/resume",
        response_model=AgentResponse,
        dependencies=[Depends(require_signature)],
    )
    async def resume(request: AgentResumeRequest) -> AgentResponse:
        return await service.resume(request)

    @app.post(
        "/internal/v1/agent/stream",
        dependencies=[Depends(require_signature)],
    )
    async def stream(request: AgentInvokeRequest) -> StreamingResponse:
        async def events() -> AsyncIterator[str]:
            yield _sse("status", {"type": "status", "sessionId": request.session_id, "message": "智能体正在处理"})
            try:
                response = await service.invoke(request)
                content = response.content
                for index in range(0, len(content), 24):
                    yield _sse("chunk", {"type": "chunk", "sessionId": request.session_id, "content": content[index:index + 24]})
                yield _sse("done", response.model_dump(mode="json"))
            except Exception:
                yield _sse("error", {"type": "error", "requestId": request.request_id, "message": "智能助手处理失败"})

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    return app


app = create_app()
