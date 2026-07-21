"""把 API DTO、LangGraph 状态和持久化快照连接起来。"""

from __future__ import annotations

import asyncio
import time
from typing import Any, Protocol

from .graph import AgentGraph
from .schemas import AgentAction, AgentInvokeRequest, AgentResponse, AgentResumeRequest
from .state import build_message_state, build_resume_state, persisted_snapshot


class MemoryWriter(Protocol):
    async def add(
        self,
        tenant_user_id: int,
        session_id: str,
        content: str,
        metadata: dict[str, Any] | None = None,
    ) -> bool: ...


class NullMemoryWriter:
    async def add(self, tenant_user_id, session_id, content, metadata=None):
        return False


class AgentService:
    def __init__(self, graph: AgentGraph, memory: MemoryWriter | None = None) -> None:
        self._graph = graph
        self._memory = memory or NullMemoryWriter()
        self._memory_tasks: set[asyncio.Task] = set()

    async def invoke(self, request: AgentInvokeRequest) -> AgentResponse:
        state = await self._graph.invoke(build_message_state(request))
        response = self._response(request.request_id, state)
        self._schedule_memory(state, response)
        return response

    async def resume(self, request: AgentResumeRequest) -> AgentResponse:
        state = await self._graph.resume(build_resume_state(request))
        response = self._response(request.request_id, state)
        self._schedule_memory(state, response)
        return response

    def _schedule_memory(self, state: dict, response: AgentResponse) -> None:
        if response.exit_reason != "COMPLETE" or state.get("phase") != "RESPONDING":
            return
        user_input = str(state.get("user_input", "")).strip()
        if not user_input or not response.content.strip():
            return
        conversation = f"用户: {user_input}\n助手: {response.content}"
        task = asyncio.create_task(self._memory.add(
            int(state.get("tenant_user_id", 0)),
            str(state.get("thread_id", "")),
            conversation,
            {"source": "chat", "timestamp": int(time.time() * 1000)},
        ))
        self._memory_tasks.add(task)
        task.add_done_callback(self._finish_memory_task)

    def _finish_memory_task(self, task: asyncio.Task) -> None:
        self._memory_tasks.discard(task)
        if not task.cancelled():
            task.exception()

    async def shutdown(self) -> None:
        if self._memory_tasks:
            await asyncio.gather(*tuple(self._memory_tasks), return_exceptions=True)

    @staticmethod
    def _response(request_id: str, state: dict) -> AgentResponse:
        action_data = state.get("action") or None
        return AgentResponse(
            request_id=request_id,
            content=str(state.get("result_content", "")),
            result_type=str(state.get("result_type", "TEXT")),
            intent=str(state.get("intent", "OPS_QUERY")),
            action=AgentAction.model_validate(action_data) if action_data else None,
            checkpoint=persisted_snapshot(state),
            exit_reason=str(state.get("exit_reason", "ERROR")),
            trace=list(state.get("node_trace", [])),
        )
