from __future__ import annotations

import json
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from python_assistant_service.app.main import create_app
from python_assistant_service.app.schemas import AgentResponse
from python_assistant_service.app.security import InMemoryReplayGuard, create_signature
from python_assistant_service.app.settings import Settings


class StubService:
    async def invoke(self, request):
        return AgentResponse(
            request_id=request.request_id,
            content="处理完成",
            result_type="TEXT",
            intent="OPS_QUERY",
            checkpoint={"thread_id": request.session_id},
            exit_reason="COMPLETE",
            trace=["router", "ops_agent", "quality_gate", "responder"],
        )

    async def resume(self, request):
        return AgentResponse(
            request_id=request.request_id,
            content="恢复完成",
            result_type="TEXT",
            intent="OPS_ACTION",
            checkpoint=request.checkpoint,
            exit_reason="COMPLETE",
            trace=["resume_router", "ops_agent", "quality_gate", "responder"],
        )


def request_payload():
    return {
        "request_id": "req-http-1",
        "idempotency_key": "msg-http-1",
        "tenant_user_id": 42,
        "username": "user42",
        "session_id": "sess-42",
        "content": "你好",
        "checkpoint": {},
        "mode": "MESSAGE",
    }


def signed_headers(secret: str, path: str, body: bytes, nonce: str | None = None):
    timestamp = str(int(time.time()))
    nonce = nonce or str(uuid.uuid4())
    return {
        "Content-Type": "application/json",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": create_signature(secret, "POST", path, timestamp, nonce, body),
    }


def test_signed_request_is_accepted_and_nonce_cannot_be_replayed():
    secret = "test-secret-with-more-than-32-characters"
    settings = Settings(require_signature=True, hmac_secret=secret)
    app = create_app(settings, StubService(), InMemoryReplayGuard())
    path = "/internal/v1/agent/invoke"
    body = json.dumps(request_payload(), ensure_ascii=False, separators=(",", ":")).encode()
    headers = signed_headers(secret, path, body, "fixed-nonce")

    with TestClient(app) as client:
        first = client.post(path, content=body, headers=headers)
        replay = client.post(path, content=body, headers=headers)

    assert first.status_code == 200
    assert first.json()["content"] == "处理完成"
    assert replay.status_code == 409


def test_invalid_signature_is_rejected():
    settings = Settings(require_signature=True, hmac_secret="correct-secret")
    app = create_app(settings, StubService(), InMemoryReplayGuard())
    path = "/internal/v1/agent/invoke"
    body = json.dumps(request_payload(), separators=(",", ":")).encode()
    headers = signed_headers("wrong-secret", path, body)

    with TestClient(app) as client:
        response = client.post(path, content=body, headers=headers)

    assert response.status_code == 401


def test_stream_preserves_existing_event_names():
    settings = Settings(require_signature=False)
    app = create_app(settings, StubService(), InMemoryReplayGuard())

    with TestClient(app) as client:
        response = client.post("/internal/v1/agent/stream", json=request_payload())

    assert response.status_code == 200
    assert "event: status" in response.text
    assert "event: chunk" in response.text
    assert "event: done" in response.text


def test_health_does_not_expose_secret():
    settings = Settings(require_signature=True, hmac_secret="secret")
    app = create_app(settings, StubService(), InMemoryReplayGuard())

    with TestClient(app) as client:
        response = client.get("/internal/v1/health")

    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert "secret" not in response.text.lower()


@pytest.mark.parametrize(
    ("settings", "expected"),
    [
        (Settings(deep_agent_enabled=False), "DISABLED"),
        (
            Settings(deep_agent_enabled=True, deepseek_enabled=True),
            "MODEL_NOT_CONFIGURED",
        ),
        (
            Settings(
                deep_agent_enabled=True,
                deepseek_enabled=True,
                deepseek_api_key="test-key",
                deep_agent_model="deepseek-reasoner",
            ),
            "UNSUPPORTED_MODEL",
        ),
        (
            Settings(
                deep_agent_enabled=True,
                deepseek_enabled=True,
                deepseek_api_key="test-key",
                deep_agent_model="deepseek-chat",
            ),
            "READY",
        ),
    ],
)
def test_health_explains_deep_agent_configuration(settings, expected):
    app = create_app(settings, StubService(), InMemoryReplayGuard())

    with TestClient(app) as client:
        response = client.get("/internal/v1/health")

    assert response.status_code == 200
    assert response.json()["deep_agent_status"] == expected
    assert response.json()["deep_agent_configured"] is (expected == "READY")
