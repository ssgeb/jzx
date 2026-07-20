"""LangGraph 状态定义与 Java Checkpoint 兼容处理。"""

from __future__ import annotations

from typing import Any
from typing_extensions import TypedDict

from .schemas import AgentInvokeRequest, AgentResumeRequest


class AgentState(TypedDict, total=False):
    thread_id: str
    user_input: str
    username: str
    tenant_user_id: int
    request_id: str
    idempotency_key: str
    resume_action_id: str
    current_route: str
    current_page_title: str
    current_node: str
    next_node: str
    route_decision: dict[str, Any]
    intent: str
    data_context: dict[str, Any]
    result_content: str
    result_type: str
    action: dict[str, Any]
    error: str
    iteration: int
    confirmed: bool
    pending_action_id: str
    exit_reason: str
    node_trace: list[str]
    route_trace: list[str]
    node_visit_count: dict[str, int]
    turn: int
    recent_msgs: list[dict[str, Any]]
    summary: str
    task_prompt: str
    task_type: str
    slots: dict[str, Any]
    missing_slots: list[str]
    intermediate: dict[str, Any]
    phase: str


PERSISTED_STATE_KEYS = frozenset(AgentState.__annotations__) - {
    "request_id",
    "idempotency_key",
    "resume_action_id",
}


def _checkpoint_state(checkpoint: dict[str, Any]) -> AgentState:
    return {
        key: value
        for key, value in checkpoint.items()
        if key in PERSISTED_STATE_KEYS and value is not None
    }


def build_message_state(request: AgentInvokeRequest) -> AgentState:
    state = _checkpoint_state(request.checkpoint)
    # 身份和当前输入必须覆盖历史 Checkpoint，不能信任客户端提交的旧值。
    state.update(
        thread_id=request.session_id,
        user_input=request.content,
        username=request.username,
        tenant_user_id=request.tenant_user_id,
        request_id=request.request_id,
        idempotency_key=request.idempotency_key,
        current_route=request.current_route or "",
        current_page_title=request.current_page_title or "",
        iteration=0,
        error="",
        exit_reason="",
        action={},
    )
    return state


def build_resume_state(request: AgentResumeRequest) -> AgentState:
    state = _checkpoint_state(request.checkpoint)
    state.update(
        thread_id=request.session_id,
        username=request.username,
        tenant_user_id=request.tenant_user_id,
        request_id=request.request_id,
        idempotency_key=request.idempotency_key,
        resume_action_id=request.action_id,
        confirmed=request.confirmed,
        iteration=0,
        error="",
        exit_reason="",
        action={},
    )
    return state


def persisted_snapshot(state: AgentState) -> dict[str, Any]:
    return {
        key: value
        for key, value in state.items()
        if key in PERSISTED_STATE_KEYS and value is not None
    }
