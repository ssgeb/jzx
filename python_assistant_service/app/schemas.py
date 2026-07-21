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


class SkillInstallRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    repository: str = Field(min_length=3, max_length=200)
    path: str = Field(min_length=1, max_length=500)
    ref: str = Field(default="main", min_length=1, max_length=200)
    requested_by: str = Field(min_length=1, max_length=100)

    @field_validator("repository")
    @classmethod
    def repository_must_be_owner_and_name(cls, value: str) -> str:
        import re

        normalized = value.strip().lower()
        component = r"[a-z0-9](?:[a-z0-9_.-]{0,99})"
        if not re.fullmatch(rf"{component}/{component}", normalized):
            raise ValueError("repository 必须使用 owner/repository 格式")
        return normalized

    @field_validator("path", "ref")
    @classmethod
    def source_part_must_be_safe(cls, value: str) -> str:
        normalized = value.strip().replace("\\", "/")
        if (
            not normalized
            or normalized.startswith("/")
            or ".." in normalized.split("/")
            or any(ord(char) < 32 for char in normalized)
        ):
            raise ValueError("Skill 来源路径或版本不安全")
        return normalized.strip("/")


class SkillRecord(BaseModel):
    name: str
    description: str
    repository: str
    source_path: str
    ref: str
    checksum: str
    status: str
    installed_at: str
    installed_by: str


class SkillListResponse(BaseModel):
    enabled: bool
    skills: list[SkillRecord] = Field(default_factory=list)
