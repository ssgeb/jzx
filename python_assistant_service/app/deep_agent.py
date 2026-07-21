"""面向 Harness 业务的 Deep Agent 主/子智能体编排。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import threading
from typing import Any, Protocol

from deepagents import (
    GeneralPurposeSubagentProfile,
    HarnessProfile,
    create_deep_agent,
    register_harness_profile,
)
from langchain.tools import tool
from langchain_core.language_models import BaseChatModel
from langchain_deepseek import ChatDeepSeek

from .clients import ToolClient
from .memory import format_memories
from .routing import has_write_intent, keyword_route
from .settings import Settings
from .state import AgentState


FILESYSTEM_AND_EXECUTION_TOOLS = frozenset(
    {"ls", "read_file", "write_file", "edit_file", "glob", "grep", "execute"}
)
AGENT_DESCRIPTIONS = {
    "DETECTION": (
        "detection-specialist",
        "检测与质检专家，查询检测任务、缺陷、处置、复核、工单和证据数据。",
    ),
    "RESOURCE": (
        "resource-specialist",
        "资源专家，查询设备、人员、模型、在线状态和资源配置。",
    ),
    "REPORT": (
        "report-specialist",
        "报表与分析专家，查询日报、周报、趋势、统计、汇总和工作量。",
    ),
    "OPS": (
        "ops-specialist",
        "运维与导航专家，查询系统状态、页面入口、业务流程和故障定位信息。",
    ),
}

_PROFILE_LOCK = threading.Lock()
_PROFILE_REGISTERED = False


class KnowledgeRetriever(Protocol):
    async def retrieve(self, query: str) -> str: ...


class MemoryReader(Protocol):
    async def search(
        self, tenant_user_id: int, session_id: str, query: str, top_k: int | None = None
    ) -> list[dict[str, Any]]: ...


class DeepAgentFactory(Protocol):
    def __call__(self, *args, **kwargs): ...


def _register_restricted_harness_profile() -> None:
    """禁用 Deep Agents 默认文件和执行工具，只保留规划与委派。"""
    global _PROFILE_REGISTERED
    if _PROFILE_REGISTERED:
        return
    with _PROFILE_LOCK:
        if _PROFILE_REGISTERED:
            return
        register_harness_profile(
            "deepseek",
            HarnessProfile(
                excluded_tools=FILESYSTEM_AND_EXECUTION_TOOLS,
                general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False),
            ),
        )
        _PROFILE_REGISTERED = True


class HarnessDeepAgent:
    """Deep Agent 主智能体：只处理查询，写操作交回确定性确认流程。"""

    def __init__(
        self,
        settings: Settings,
        tool_client: ToolClient,
        knowledge: KnowledgeRetriever,
        memory: MemoryReader,
        *,
        model: BaseChatModel | None = None,
        agent_factory: DeepAgentFactory = create_deep_agent,
    ) -> None:
        self._settings = settings
        self._tool_client = tool_client
        self._knowledge = knowledge
        self._memory = memory
        self._agent_factory = agent_factory
        self._model = model
        _register_restricted_harness_profile()

    @property
    def available(self) -> bool:
        if self._model is not None:
            return self._settings.deep_agent_enabled
        return bool(
            self._settings.deep_agent_enabled
            and self._settings.deepseek_enabled
            and self._settings.deepseek_api_key
            and self._settings.deepseek_model == "deepseek-chat"
        )

    async def invoke(self, state: AgentState) -> AgentState | None:
        query = str(state.get("user_input", "")).strip()
        if not self.available or not query or has_write_intent(query.lower()):
            return None

        rag_context, memories, degraded = await self._load_context(state, query)
        memory_context = format_memories(memories)
        model = self._model or self._build_model()
        tools = {
            agent: self._build_query_tool(agent, state, rag_context, memory_context)
            for agent in AGENT_DESCRIPTIONS
        }
        subagents = [
            {
                "name": name,
                "description": description,
                "system_prompt": self._subagent_prompt(agent, description),
                "tools": [tools[agent]],
                "model": model,
            }
            for agent, (name, description) in AGENT_DESCRIPTIONS.items()
        ]
        graph = self._agent_factory(
            model=model,
            tools=[],
            system_prompt=self._supervisor_prompt(state, rag_context, memory_context),
            subagents=subagents,
            name="doorhandlecatch-harness-agent",
        )
        result = await graph.ainvoke(
            {"messages": [{"role": "user", "content": query}]},
            config={"recursion_limit": self._settings.deep_agent_max_iterations},
        )
        content = self._last_answer(result)
        if not content:
            raise RuntimeError("Harness Deep Agent 未返回有效回答")
        return self._complete_state(state, content, rag_context, memories, degraded)

    async def _load_context(
        self, state: AgentState, query: str
    ) -> tuple[str, list[dict[str, Any]], list[str]]:
        rag_result, memory_result = await asyncio.gather(
            self._knowledge.retrieve(query),
            self._memory.search(
                int(state.get("tenant_user_id", 0)),
                str(state.get("thread_id", "")),
                query,
                self._settings.memory_top_k,
            ),
            return_exceptions=True,
        )
        degraded: list[str] = []
        if isinstance(rag_result, BaseException):
            rag_context = ""
            degraded.append("RAG")
        else:
            rag_context = rag_result
        if isinstance(memory_result, BaseException):
            memories: list[dict[str, Any]] = []
            degraded.append("MEMORY")
        else:
            memories = memory_result
        return rag_context, memories, degraded

    def _build_model(self) -> BaseChatModel:
        return ChatDeepSeek(
            model=self._settings.deepseek_model,
            api_key=self._settings.deepseek_api_key,
            api_base=self._settings.deepseek_base_url,
            temperature=0,
            timeout=self._settings.deep_agent_model_timeout_seconds,
            max_retries=self._settings.deep_agent_model_max_retries,
        )

    def _build_query_tool(
        self,
        agent: str,
        state: AgentState,
        rag_context: str,
        memory_context: str,
    ):
        tool_name = f"query_{agent.lower()}"

        @tool(tool_name)
        async def query_business_data(question: str) -> str:
            """查询当前租户用户有权访问的实时业务数据。"""
            original = str(state.get("user_input", ""))
            prompt_parts = [f"原始用户问题：{original}", f"本专家需要解决：{question}"]
            if rag_context:
                prompt_parts.append(
                    "系统知识库参考（实时状态以业务数据为准）：\n" + rag_context
                )
            if memory_context:
                prompt_parts.append("当前用户历史记忆：\n" + memory_context)
            digest = hashlib.sha256(question.encode("utf-8")).hexdigest()[:12]
            result = await self._tool_client.execute(
                agent,
                "query",
                {
                    "requestId": state.get("request_id"),
                    "tenantUserId": state.get("tenant_user_id"),
                    "username": state.get("username"),
                    "sessionId": state.get("thread_id"),
                    "prompt": "\n\n".join(prompt_parts),
                    "slots": dict(state.get("slots", {})),
                    "currentRoute": state.get("current_route", ""),
                    "currentPageTitle": state.get("current_page_title", ""),
                },
                f"{state.get('idempotency_key', '')}:deep:{agent.lower()}:{digest}",
            )
            return json.dumps(result, ensure_ascii=False)[:12000]

        return query_business_data

    @staticmethod
    def _subagent_prompt(agent: str, description: str) -> str:
        return (
            f"你是 DoorHandleCatch Harness Agent 的{description}"
            f"你只能使用 query_{agent.lower()} 读取当前用户有权访问的数据。"
            "不得伪造数据，不得尝试修改状态，不得把任务再委派给其他智能体。"
            "将工具结果整理为简洁、可核对的中文结论后返回主 Agent。"
        )

    @staticmethod
    def _supervisor_prompt(
        state: AgentState, rag_context: str, memory_context: str
    ) -> str:
        context_parts = [
            "你是 DoorHandleCatch 工业质检平台的 Harness 主 Agent。",
            "你负责分解用户目标、使用 write_todos 规划复杂任务，并通过 task 委派给检测、资源、报表或运维专家。",
            "涉及多个领域时可分别委派，但简单问题只委派给一个最匹配的专家。",
            "不得自行构造实时业务数据；必须以子 Agent 返回的 Java 业务工具结果为准。",
            "当前链路只处理读查询。如果判断用户想修改数据，明确说明需要进入系统的人工确认流程，不要尝试执行。",
            "最终用中文直接回答用户，合并各子 Agent 结论并指出不确定信息。",
            f"当前页面：{state.get('current_page_title') or '未知'}；路由：{state.get('current_route') or '未知'}。",
        ]
        if rag_context:
            context_parts.append("公共知识库参考：\n" + rag_context)
        if memory_context:
            context_parts.append("当前用户记忆：\n" + memory_context)
        return "\n\n".join(context_parts)

    def _complete_state(
        self,
        state: AgentState,
        content: str,
        rag_context: str,
        memories: list[dict[str, Any]],
        degraded: list[str],
    ) -> AgentState:
        result = AgentState(**state)
        route = keyword_route(str(state.get("user_input", "")))
        turn = int(state.get("turn", 0)) + 1
        recent = list(state.get("recent_msgs", []))
        recent.extend(
            [
                {"role": "user", "content": state.get("user_input", ""), "turn": turn},
                {"role": "assistant", "content": content, "turn": turn},
            ]
        )
        trace = list(state.get("node_trace", [])) + ["context", "harness_deep_agent"]
        result.update(
            turn=turn,
            route_decision=route,
            intent=route["intent"],
            rag_context=rag_context,
            user_memories=memories,
            user_memory_context=format_memories(memories),
            context_degraded=degraded,
            result_content=content,
            result_type="TEXT",
            phase="RESPONDING",
            exit_reason="COMPLETE",
            current_node="harness_deep_agent",
            node_trace=trace[-self._settings.max_trace_size :],
            recent_msgs=recent[-20:],
        )
        return result

    @staticmethod
    def _last_answer(result: Any) -> str:
        if not isinstance(result, dict):
            return ""
        for message in reversed(result.get("messages", [])):
            content = getattr(message, "content", None)
            if content is None and isinstance(message, dict):
                content = message.get("content")
            if isinstance(content, str) and content.strip():
                return content.strip()
            if isinstance(content, list):
                texts = []
                for block in content:
                    if isinstance(block, str):
                        texts.append(block)
                    elif isinstance(block, dict) and isinstance(block.get("text"), str):
                        texts.append(block["text"])
                if texts:
                    return "\n".join(texts).strip()
        return ""
