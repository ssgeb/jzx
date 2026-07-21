import asyncio
import json
from typing import Any, Sequence

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import PrivateAttr

from python_assistant_service.app.deep_agent import (
    AGENT_DESCRIPTIONS,
    FILESYSTEM_AND_EXECUTION_TOOLS,
    HarnessDeepAgent,
)
from python_assistant_service.app.schemas import AgentInvokeRequest
from python_assistant_service.app.service import AgentService
from python_assistant_service.app.settings import Settings
from python_assistant_service.app.state import build_message_state


class RecordingTools:
    def __init__(self):
        self.calls = []

    async def execute(self, agent, operation, payload, idempotency_key):
        self.calls.append((agent, operation, payload, idempotency_key))
        return {"content": f"{agent} 实时数据"}


class FixedKnowledge:
    async def retrieve(self, query):
        return "[公共知识]设备离线时检查网络。"


class FixedMemory:
    async def search(self, tenant_user_id, session_id, query, top_k=None):
        return [{"memory": "用户关心设备编号"}]

    async def add(self, tenant_user_id, session_id, content, metadata=None):
        return True


class FakeCompiledAgent:
    def __init__(self, owner):
        self.owner = owner

    async def ainvoke(self, inputs, config):
        detection = next(
            item for item in self.owner.kwargs["subagents"]
            if item["name"] == "detection-specialist"
        )
        self.owner.tool_result = await detection["tools"][0].ainvoke(
            {"question": "查询检测任务和缺陷证据"}
        )
        self.owner.config = config
        return {"messages": [{"role": "assistant", "content": "Harness 综合结论"}]}


class RecordingFactory:
    def __init__(self):
        self.calls = 0
        self.kwargs = None
        self.tool_result = None
        self.config = None

    def __call__(self, *args, **kwargs):
        self.calls += 1
        self.kwargs = kwargs
        return FakeCompiledAgent(self)


def request(content="查询检测任务和设备在线状态"):
    return AgentInvokeRequest(
        request_id="req-deep-1",
        idempotency_key="idem-deep-1",
        tenant_user_id=42,
        username="alice",
        session_id="sess-deep-1",
        content=content,
        checkpoint={},
        current_route="/quality",
        current_page_title="质检中心",
    )


def build_harness(factory, tools=None, knowledge=None, memory=None):
    return HarnessDeepAgent(
        Settings(
            deep_agent_enabled=True,
            deepseek_enabled=True,
            deepseek_api_key="test-key",
            deep_agent_max_iterations=36,
        ),
        tools or RecordingTools(),
        knowledge or FixedKnowledge(),
        memory or FixedMemory(),
        model=object(),
        agent_factory=factory,
    )


def test_deep_agent_model_is_independent_from_legacy_router_model():
    harness = HarnessDeepAgent(
        Settings(
            deep_agent_enabled=True,
            deepseek_enabled=True,
            deepseek_api_key="test-key",
            deepseek_model="legacy-router-model",
            deep_agent_model="deepseek-chat",
        ),
        RecordingTools(),
        FixedKnowledge(),
        FixedMemory(),
    )

    assert harness.available is True


def test_harness_deep_agent_uses_four_restricted_subagents_and_fixed_java_tool():
    factory = RecordingFactory()
    tools = RecordingTools()
    harness = build_harness(factory, tools=tools)

    result = asyncio.run(harness.invoke(build_message_state(request())))

    assert result["result_content"] == "Harness 综合结论"
    assert result["node_trace"] == ["context", "harness_deep_agent"]
    assert result["exit_reason"] == "COMPLETE"
    assert factory.kwargs["tools"] == []
    assert factory.config == {"recursion_limit": 36}
    assert {item["name"] for item in factory.kwargs["subagents"]} == {
        value[0] for value in AGENT_DESCRIPTIONS.values()
    }
    for subagent in factory.kwargs["subagents"]:
        assert len(subagent["tools"]) == 1
        assert subagent["tools"][0].name.startswith("query_")
        assert subagent["tools"][0].name not in FILESYSTEM_AND_EXECUTION_TOOLS

    assert tools.calls[0][0:2] == ("DETECTION", "query")
    assert tools.calls[0][2]["tenantUserId"] == 42
    assert tools.calls[0][2]["sessionId"] == "sess-deep-1"
    assert "设备离线时检查网络" in tools.calls[0][2]["prompt"]
    assert "用户关心设备编号" in tools.calls[0][2]["prompt"]
    assert json.loads(factory.tool_result)["content"] == "DETECTION 实时数据"


class RecordingChatModel(BaseChatModel):
    _bound_tool_names: list[list[str]] = PrivateAttr(default_factory=list)

    @property
    def _llm_type(self):
        return "deepseek"

    def _get_ls_params(self, **kwargs):
        return {"ls_provider": "deepseek", "ls_model_name": "deepseek-chat"}

    def bind_tools(self, tools: Sequence[Any], **kwargs):
        names = []
        for item in tools:
            if isinstance(item, dict):
                names.append(item.get("function", {}).get("name", ""))
            else:
                names.append(getattr(item, "name", ""))
        self._bound_tool_names.append(names)
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        return ChatResult(
            generations=[ChatGeneration(message=AIMessage(content="权限面检查完成"))]
        )


def test_registered_harness_profile_hides_file_and_execution_tools_from_model():
    model = RecordingChatModel()
    tools = RecordingTools()
    harness = HarnessDeepAgent(
        Settings(deep_agent_enabled=True),
        tools,
        FixedKnowledge(),
        FixedMemory(),
        model=model,
    )
    state = build_message_state(request())
    query_tools = {
        agent: harness._build_query_tool(agent, state, "", "")
        for agent in AGENT_DESCRIPTIONS
    }
    subagents = [
        {
            "name": name,
            "description": description,
            "system_prompt": harness._subagent_prompt(agent, description),
            "tools": [query_tools[agent]],
            "model": model,
        }
        for agent, (name, description) in AGENT_DESCRIPTIONS.items()
    ]
    graph = harness._agent_factory(
        model=model,
        tools=[],
        system_prompt="Harness 权限面测试",
        subagents=subagents,
        name="harness-permission-test",
    )

    asyncio.run(graph.ainvoke({"messages": [{"role": "user", "content": "查询检测"}]}))

    visible = set(model._bound_tool_names[-1])
    assert not visible.intersection(FILESYSTEM_AND_EXECUTION_TOOLS)
    assert {"task", "write_todos"}.issubset(visible)


def test_harness_deep_agent_refuses_write_intent_before_model_or_tool_call():
    factory = RecordingFactory()
    tools = RecordingTools()
    harness = build_harness(factory, tools=tools)

    result = asyncio.run(harness.invoke(build_message_state(request("删除检测任务 TASK-1001"))))

    assert result is None
    assert factory.calls == 0
    assert tools.calls == []


class BrokenContext:
    async def retrieve(self, query):
        raise RuntimeError("rag unavailable")

    async def search(self, tenant_user_id, session_id, query, top_k=None):
        raise RuntimeError("memory unavailable")


def test_harness_context_failure_degrades_without_blocking_agent():
    factory = RecordingFactory()
    broken = BrokenContext()
    harness = build_harness(factory, knowledge=broken, memory=broken)

    result = asyncio.run(harness.invoke(build_message_state(request())))

    assert result["exit_reason"] == "COMPLETE"
    assert result["context_degraded"] == ["RAG", "MEMORY"]


class BrokenDeepAgent:
    available = True

    async def invoke(self, state):
        raise RuntimeError("deep agent unavailable")


class FallbackGraph:
    async def invoke(self, state):
        return {
            **state,
            "intent": "OPS_QUERY",
            "result_content": "已降级到 LangGraph",
            "result_type": "TEXT",
            "phase": "RESPONDING",
            "exit_reason": "COMPLETE",
            "node_trace": ["router", "ops_agent", "responder"],
        }


def test_agent_service_falls_back_when_deep_agent_fails():
    service = AgentService(FallbackGraph(), deep_agent=BrokenDeepAgent())

    response = asyncio.run(service.invoke(request("查看系统状态")))

    assert response.content == "已降级到 LangGraph"
    assert response.exit_reason == "COMPLETE"
