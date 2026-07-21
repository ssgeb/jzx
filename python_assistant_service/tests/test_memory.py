from __future__ import annotations

import asyncio
import json

import httpx

from python_assistant_service.app.memory import MemoryServiceClient, sanitize_memory_content
from python_assistant_service.app.settings import Settings


def test_memory_search_and_add_use_user_and_session_scope():
    calls = []

    async def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(await request.aread())
        calls.append((request.url.path, payload))
        if request.url.path.endswith("/search"):
            return httpx.Response(200, json={"data": [{"id": "m1", "memory": "记忆内容"}]})
        return httpx.Response(200, json={"data": {"results": []}})

    http_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    settings = Settings(memory_service_url="http://memory-service", memory_top_k=3)
    client = MemoryServiceClient(settings, http_client)

    async def scenario():
        memories = await client.search(42, "sess-42", "查询任务")
        await client.search(43, "sess-43", "查询任务")
        added = await client.add(42, "sess-42", "用户: hello\n助手: world")
        await http_client.aclose()
        return memories, added

    memories, added = asyncio.run(scenario())

    assert memories[0]["memory"] == "记忆内容"
    assert added is True
    assert calls[0][1]["user_id"] == "doorhandlecatch:user:42"
    assert calls[1][1]["user_id"] == "doorhandlecatch:user:43"
    assert calls[1][1]["run_id"] == "sess-43"
    for _, payload in (calls[0], calls[2]):
        assert payload["user_id"] == "doorhandlecatch:user:42"
        assert payload["app_id"] == "doorhandlecatch"
        assert payload["run_id"] == "sess-42"
    assert calls[0][1]["top_k"] == 3


def test_memory_content_is_sanitized_before_storage():
    text = "password=123456 token:abcdef1234567890 Bearer abcdefghijklmnop"

    sanitized = sanitize_memory_content(text)

    assert "123456" not in sanitized
    assert "abcdef1234567890" not in sanitized
    assert "abcdefghijklmnop" not in sanitized
    assert sanitized.count("[REDACTED]") == 3
