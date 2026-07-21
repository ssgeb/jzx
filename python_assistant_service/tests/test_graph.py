from __future__ import annotations

import asyncio
from typing import Any

import pytest

from python_assistant_service.app.graph import AgentGraph
from python_assistant_service.app.schemas import AgentInvokeRequest, AgentResumeRequest
from python_assistant_service.app.service import AgentService
from python_assistant_service.app.settings import Settings


class NoModel:
    async def classify(self, content: str, context: dict[str, Any]):
        return None


class RecordingTools:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, dict[str, Any], str]] = []

    async def execute(self, agent, operation, payload, idempotency_key):
        self.calls.append((agent, operation, payload, idempotency_key))
        return {
            "content": f"{agent} {operation} 执行完成",
            "resultType": "TEXT",
        }


@pytest.fixture
def fixture():
    tools = RecordingTools()
    settings = Settings(require_signature=False, max_graph_iterations=15)
    service = AgentService(AgentGraph(settings, tools, NoModel()))
    return service, tools


def message_request(content: str, checkpoint: dict[str, Any] | None = None):
    return AgentInvokeRequest(
        request_id="req-1",
        idempotency_key="msg-1",
        tenant_user_id=42,
        username="user42",
        session_id="sess-42",
        content=content,
        checkpoint=checkpoint or {},
    )


def test_detection_query_runs_specialist_quality_gate_and_responder(fixture):
    service, tools = fixture

    response = asyncio.run(service.invoke(message_request("查询检测任务进度")))

    assert response.exit_reason == "COMPLETE"
    assert response.intent == "DETECTION_QUERY"
    assert response.content == "DETECTION query 执行完成"
    assert response.trace == ["context", "router", "detection_agent", "quality_gate", "responder"]
    assert tools.calls[0][0:2] == ("DETECTION", "query")


def test_missing_upload_folder_is_asked_before_creating_action(fixture):
    service, tools = fixture

    response = asyncio.run(service.invoke(message_request("上传图片并开始处理")))

    assert response.exit_reason == "COMPLETE"
    assert response.content == "还需要你补充：图片文件夹路径。"
    assert response.checkpoint["phase"] == "COLLECTING"
    assert tools.calls == []


def test_follow_up_slot_completes_previous_upload_action(fixture):
    service, tools = fixture
    first = asyncio.run(service.invoke(message_request("上传图片")))

    follow_up = AgentInvokeRequest(
        request_id="req-2",
        idempotency_key="msg-2",
        tenant_user_id=42,
        username="user42",
        session_id="sess-42",
        content=r"C:\inspection\batch-01",
        checkpoint=first.checkpoint,
    )
    pending = asyncio.run(service.invoke(follow_up))

    assert pending.exit_reason == "PENDING_CONFIRMATION"
    assert pending.intent == "DETECTION_ACTION"
    assert pending.action is not None
    assert pending.action.task_prompt == "上传图片"
    assert pending.action.parameters["folderPath"] == r"C:\inspection\batch-01"
    assert pending.checkpoint["task_prompt"] == "上传图片"
    assert tools.calls == []


def test_write_action_interrupts_then_resumes_once(fixture):
    service, tools = fixture
    pending = asyncio.run(service.invoke(message_request("删除设备 DEV-1001")))

    assert pending.exit_reason == "PENDING_CONFIRMATION"
    assert pending.action is not None
    assert tools.calls == []
    assert [message["role"] for message in pending.checkpoint["recent_msgs"]] == ["user", "assistant"]

    resumed = asyncio.run(service.resume(
        AgentResumeRequest(
            request_id="req-2",
            idempotency_key="action-1",
            tenant_user_id=42,
            username="user42",
            session_id="sess-42",
            action_id=pending.action.action_id,
            confirmed=True,
            checkpoint=pending.checkpoint,
        )
    ))

    assert resumed.exit_reason == "COMPLETE"
    assert resumed.content == "RESOURCE action 执行完成"
    assert len(tools.calls) == 1
    assert tools.calls[0][0:2] == ("RESOURCE", "action")
    assert tools.calls[0][3] == "action-1"
    assert [message["role"] for message in resumed.checkpoint["recent_msgs"]] == ["user", "assistant", "assistant"]


def test_trusted_request_identity_overrides_checkpoint_identity(fixture):
    service, tools = fixture

    asyncio.run(service.invoke(
        message_request(
            "查询检测任务",
            checkpoint={"tenant_user_id": 999, "username": "attacker"},
        )
    ))

    payload = tools.calls[0][2]
    assert payload["tenantUserId"] == 42
    assert payload["username"] == "user42"


def test_resume_rejects_action_id_that_does_not_match_checkpoint(fixture):
    service, tools = fixture
    pending = asyncio.run(service.invoke(message_request("删除设备 DEV-1001")))

    response = asyncio.run(service.resume(
        AgentResumeRequest(
            request_id="req-3",
            idempotency_key="action-2",
            tenant_user_id=42,
            username="user42",
            session_id="sess-42",
            action_id="wrong-action-id",
            confirmed=True,
            checkpoint=pending.checkpoint,
        )
    ))

    assert response.exit_reason == "ERROR"
    assert tools.calls == []


class FixedKnowledge:
    async def retrieve(self, query):
        return "[系统知识库检索结果]\n设备离线时应检查网络。"


class RecordingMemory:
    def __init__(self):
        self.search_calls = []
        self.add_calls = []

    async def search(self, tenant_user_id, session_id, query, top_k=None):
        self.search_calls.append((tenant_user_id, session_id, query, top_k))
        return [{"id": "m1", "memory": "用户偏好查看设备编号", "score": 0.9}]

    async def add(self, tenant_user_id, session_id, content, metadata=None):
        self.add_calls.append((tenant_user_id, session_id, content, metadata))
        return True


def test_context_node_injects_rag_and_scoped_memory_without_persisting_them():
    tools = RecordingTools()
    memory = RecordingMemory()
    settings = Settings(require_signature=False)
    service = AgentService(
        AgentGraph(settings, tools, NoModel(), FixedKnowledge(), memory),
        memory,
    )

    async def scenario():
        response = await service.invoke(message_request("查询设备在线状态"))
        await service.shutdown()
        return response

    response = asyncio.run(scenario())

    assert memory.search_calls == [(42, "sess-42", "查询设备在线状态", 5)]
    prompt = tools.calls[0][2]["prompt"]
    assert "设备离线时应检查网络" in prompt
    assert "用户偏好查看设备编号" in prompt
    assert "rag_context" not in response.checkpoint
    assert "user_memories" not in response.checkpoint
    assert memory.add_calls[0][0:2] == (42, "sess-42")
    assert "用户: 查询设备在线状态" in memory.add_calls[0][2]


class BrokenContext:
    async def retrieve(self, query):
        raise RuntimeError("rag unavailable")

    async def search(self, tenant_user_id, session_id, query, top_k=None):
        raise RuntimeError("memory unavailable")


def test_context_dependencies_degrade_without_failing_business_query():
    tools = RecordingTools()
    settings = Settings(require_signature=False)
    broken = BrokenContext()
    service = AgentService(AgentGraph(settings, tools, NoModel(), broken, broken))

    response = asyncio.run(service.invoke(message_request("查询检测任务")))

    assert response.exit_reason == "COMPLETE"
    assert response.content == "DETECTION query 执行完成"
