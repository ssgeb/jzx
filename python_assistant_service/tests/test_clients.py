from __future__ import annotations

import asyncio
import json

import httpx

from python_assistant_service.app.clients import JavaToolClient
from python_assistant_service.app.security import create_signature
from python_assistant_service.app.settings import Settings


def test_java_tool_client_signs_exact_body_and_unwraps_result():
    secret = "shared-secret-for-client-contract-test"

    async def handler(request: httpx.Request) -> httpx.Response:
        body = await request.aread()
        timestamp = request.headers["X-Timestamp"]
        nonce = request.headers["X-Nonce"]
        expected = create_signature(
            secret,
            request.method,
            request.url.path,
            timestamp,
            nonce,
            body,
        )
        assert request.headers["X-Signature"] == expected
        payload = json.loads(body)
        assert payload["idempotencyKey"] == "msg-101"
        assert payload["tenantUserId"] == 42
        return httpx.Response(
            200,
            json={"code": 200, "message": "success", "data": {
                "content": "Java 工具结果",
                "resultType": "TEXT",
            }},
        )

    settings = Settings(
        hmac_secret=secret,
        java_tool_base_url="http://java-service",
    )
    async_client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    client = JavaToolClient(settings, async_client)

    result = asyncio.run(client.execute(
        "DETECTION",
        "query",
        {"requestId": "req-1", "tenantUserId": 42},
        "msg-101",
    ))
    asyncio.run(async_client.aclose())

    assert result["content"] == "Java 工具结果"
    assert result["resultType"] == "TEXT"
