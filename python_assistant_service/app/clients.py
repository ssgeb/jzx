"""模型与 Java 业务工具客户端。"""

from __future__ import annotations

import json
import time
import uuid
from typing import Any, Protocol

import httpx

from .security import create_signature
from .settings import Settings


class ToolClient(Protocol):
    async def execute(
        self,
        agent: str,
        operation: str,
        payload: dict[str, Any],
        idempotency_key: str,
    ) -> dict[str, Any]: ...


class JavaToolClient:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        self._client = client or httpx.AsyncClient(
            timeout=settings.java_tool_timeout_seconds
        )

    async def execute(
        self,
        agent: str,
        operation: str,
        payload: dict[str, Any],
        idempotency_key: str,
    ) -> dict[str, Any]:
        if not self._settings.java_tool_base_url:
            raise RuntimeError("尚未配置 JAVA_AGENT_TOOL_BASE_URL")
        if not self._settings.hmac_secret:
            raise RuntimeError("尚未配置 ASSISTANT_HMAC_SECRET")

        path = f"/internal/v1/agent-tools/{agent.lower()}/{operation}"
        body = json.dumps(
            {"idempotencyKey": idempotency_key, **payload},
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        timestamp = str(int(time.time()))
        nonce = str(uuid.uuid4())
        signature = create_signature(
            self._settings.hmac_secret, "POST", path, timestamp, nonce, body
        )
        response = await self._client.post(
            self._settings.java_tool_base_url.rstrip("/") + path,
            content=body,
            headers={
                "Content-Type": "application/json",
                "X-Timestamp": timestamp,
                "X-Nonce": nonce,
                "X-Signature": signature,
                "X-Request-Id": str(payload.get("requestId", "")),
            },
        )
        response.raise_for_status()
        result = response.json()
        if isinstance(result, dict) and "data" in result:
            data = result["data"]
            return data if isinstance(data, dict) else {"content": str(data)}
        if not isinstance(result, dict):
            return {"content": str(result)}
        return result


class IntentModel(Protocol):
    async def classify(self, content: str, context: dict[str, Any]) -> dict[str, Any] | None: ...


class DeepSeekIntentModel:
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        self._settings = settings
        self._client = client or httpx.AsyncClient(timeout=20.0)

    async def classify(self, content: str, context: dict[str, Any]) -> dict[str, Any] | None:
        if not (
            self._settings.deepseek_enabled and self._settings.deepseek_api_key
        ):
            return None
        prompt = (
            "你是工业质检助手的意图路由器。只输出 JSON，字段为 "
            "intent、target_agent、is_action、slot_updates。"
            "intent 只能是 NEW_TASK、SUPPLEMENT、MODIFY、FOLLOWUP、CHITCHAT；"
            "target_agent 只能是 DETECTION、RESOURCE、REPORT、OPS。\n"
            f"历史上下文：{json.dumps(context, ensure_ascii=False)}\n"
            f"用户输入：{content}"
        )
        response = await self._client.post(
            self._settings.deepseek_base_url.rstrip("/") + "/chat/completions",
            headers={"Authorization": f"Bearer {self._settings.deepseek_api_key}"},
            json={
                "model": self._settings.deepseek_model,
                "temperature": 0.1,
                "response_format": {"type": "json_object"},
                "messages": [{"role": "user", "content": prompt}],
            },
        )
        response.raise_for_status()
        raw = response.json()["choices"][0]["message"]["content"]
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else None
