"""Mem0 服务客户端、作用域约束和敏感数据脱敏。"""

from __future__ import annotations

import re
from typing import Any

import httpx

from .settings import Settings


SENSITIVE_PATTERNS = (
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----", re.I),
    re.compile(r"(?i)(password|passwd|pwd|db_password|redis_password|secret|token|api[_-]?key|access[_-]?key[_-]?secret)\s*[:=]\s*[^\s,;\"']+"),
    re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._\-+/=]{16,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"),
)


def sanitize_memory_content(content: str) -> str:
    sanitized = content
    for pattern in SENSITIVE_PATTERNS:
        sanitized = pattern.sub("[REDACTED]", sanitized)
    return sanitized


class MemoryServiceClient:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        timeout = httpx.Timeout(
            settings.memory_read_timeout_seconds,
            connect=settings.memory_connect_timeout_seconds,
        )
        self._client = client or httpx.AsyncClient(timeout=timeout)
        self._owns_client = client is None

    @staticmethod
    def scope(tenant_user_id: int, session_id: str) -> dict[str, str]:
        if tenant_user_id <= 0 or not session_id.strip():
            raise ValueError("tenant_user_id 和 session_id 不能为空")
        return {
            "user_id": f"doorhandlecatch:user:{tenant_user_id}",
            "app_id": "doorhandlecatch",
            "run_id": session_id,
        }

    async def search(
        self,
        tenant_user_id: int,
        session_id: str,
        query: str,
        top_k: int | None = None,
    ) -> list[dict[str, Any]]:
        if not self._settings.memory_enabled:
            return []
        response = await self._client.post(
            self._settings.memory_service_url.rstrip("/") + "/memories/search",
            json={
                **self.scope(tenant_user_id, session_id),
                "query": query,
                "top_k": top_k or self._settings.memory_top_k,
            },
        )
        response.raise_for_status()
        payload = response.json()
        data = payload.get("data", []) if isinstance(payload, dict) else []
        return [item for item in data if isinstance(item, dict)]

    async def add(
        self,
        tenant_user_id: int,
        session_id: str,
        content: str,
        metadata: dict[str, Any] | None = None,
    ) -> bool:
        if not self._settings.memory_enabled:
            return False
        response = await self._client.post(
            self._settings.memory_service_url.rstrip("/") + "/memories/add",
            json={
                **self.scope(tenant_user_id, session_id),
                "content": sanitize_memory_content(content),
                "metadata": metadata or {},
            },
        )
        response.raise_for_status()
        return True

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()


def format_memories(memories: list[dict[str, Any]]) -> str:
    lines = ["[用户历史记忆]"]
    for item in memories:
        content = item.get("memory")
        if isinstance(content, str) and content.strip():
            lines.append(f"- {content.strip()}")
    return "\n".join(lines) if len(lines) > 1 else ""
