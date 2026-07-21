"""Java 与 Python 之间的稳定数据契约。"""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, field_validator


class AgentMode(str, Enum):
    MESSAGE = "MESSAGE"
    RESUME = "RESUME"


class AgentAction(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action_id: str
    intent: str
    target_agent: str
    preview: str
    task_prompt: str
    parameters: dict[str, Any] = Field(default_factory=dict)


class AgentInvokeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=1, max_length=128)
    idempotency_key: str = Field(min_length=1, max_length=200)
    tenant_user_id: int = Field(gt=0)
    username: str = Field(min_length=1, max_length=100)
    session_id: str = Field(min_length=1, max_length=128)
    content: str = Field(min_length=1, max_length=20_000)
    current_route: str | None = Field(default=None, max_length=500)
    current_page_title: str | None = Field(default=None, max_length=200)
    checkpoint: dict[str, Any] = Field(default_factory=dict)
    mode: AgentMode = AgentMode.MESSAGE

    @field_validator("content")
    @classmethod
    def content_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("content 不能为空白")
        return value


class AgentResumeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(min_length=1, max_length=128)
    idempotency_key: str = Field(min_length=1, max_length=200)
    tenant_user_id: int = Field(gt=0)
    username: str = Field(min_length=1, max_length=100)
    session_id: str = Field(min_length=1, max_length=128)
    action_id: str = Field(min_length=1, max_length=128)
    confirmed: bool
    checkpoint: dict[str, Any]
    mode: AgentMode = AgentMode.RESUME


class AgentResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str
    content: str
    result_type: str
    intent: str
    action: AgentAction | None = None
    checkpoint: dict[str, Any]
    exit_reason: str
    trace: list[str] = Field(default_factory=list)


class HealthResponse(BaseModel):
    status: str
    service: str
    graph_version: str
    signature_required: bool
    java_tools_configured: bool
    model_configured: bool
    deep_agent_configured: bool = False
    deep_agent_status: str = "MODEL_NOT_CONFIGURED"
    rag_chunks: int
    memory_configured: bool
