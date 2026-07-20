"""把 API DTO、LangGraph 状态和持久化快照连接起来。"""

from __future__ import annotations

from .graph import AgentGraph
from .schemas import AgentAction, AgentInvokeRequest, AgentResponse, AgentResumeRequest
from .state import build_message_state, build_resume_state, persisted_snapshot


class AgentService:
    def __init__(self, graph: AgentGraph) -> None:
        self._graph = graph

    async def invoke(self, request: AgentInvokeRequest) -> AgentResponse:
        state = await self._graph.invoke(build_message_state(request))
        return self._response(request.request_id, state)

    async def resume(self, request: AgentResumeRequest) -> AgentResponse:
        state = await self._graph.resume(build_resume_state(request))
        return self._response(request.request_id, state)

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
