"""面向 Harness 业务的 Deep Agent 主/子智能体编排。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import threading
import uuid
from dataclasses import dataclass, field
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

from .agent_config import (
    AgentConfigError,
    SubagentCatalog,
    SubagentDefinition,
    ToolDefinition,
)
from .loader import YamlSubagentLoader
from .clients import ToolClient
from .memory import format_memories
from .routing import has_write_intent, keyword_route
from .settings import Settings
from .state import AgentState


FILESYSTEM_AND_EXECUTION_TOOLS = frozenset(
    {"ls", "read_file", "write_file", "edit_file", "glob", "grep", "execute"}
)
_PROFILE_LOCK = threading.Lock()
_PROFILE_REGISTERED = False


@dataclass
class ToolEvidence:
    successful_calls: list[dict[str, str]] = field(default_factory=list)
    failed_agents: list[str] = field(default_factory=list)
    pending_approval: dict[str, str] | None = None


def deep_agent_configuration_status(settings: Settings) -> str:
    """返回不包含密钥的 Deep Agent 配置状态，供健康检查和运行判断共用。"""
    if not settings.deep_agent_enabled:
        return "DISABLED"
    if not settings.deepseek_enabled or not settings.deepseek_api_key:
        return "MODEL_NOT_CONFIGURED"
    if settings.deep_agent_model != "deepseek-chat":
        return "UNSUPPORTED_MODEL"
    return "READY"


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
        config_loader: YamlSubagentLoader | None = None,
    ) -> None:
        self._settings = settings
        self._tool_client = tool_client
        self._knowledge = knowledge
        self._memory = memory
        self._agent_factory = agent_factory
        self._model = model
        self._config_loader = config_loader or YamlSubagentLoader(
            settings.subagent_config_path
        )
        _register_restricted_harness_profile()

    @property
    def available(self) -> bool:
        return self.configuration_status == "READY"

    @property
    def configuration_status(self) -> str:
        base_status = (
            "READY" if self._model is not None and self._settings.deep_agent_enabled
            else "DISABLED" if self._model is not None
            else deep_agent_configuration_status(self._settings)
        )
        if base_status != "READY":
            return base_status
        try:
            self._config_loader.load()
        except AgentConfigError:
            return "SUBAGENT_CONFIG_INVALID"
        return "READY"

    async def invoke(self, state: AgentState) -> AgentState | None:
        query = str(state.get("user_input", "")).strip()
        if not self.available or not query or has_write_intent(query.lower()):
            return None

        catalog = self._config_loader.load()

        rag_context, memories, degraded = await self._load_context(state, query)
        memory_context = format_memories(memories)
        model = self._model or self._build_model()
        evidence = ToolEvidence()
        tools = {
            tool_name: self._build_query_tool(
                tool_name,
                definition,
                state,
                rag_context,
                memory_context,
                evidence,
            )
            for tool_name, definition in catalog.tools.items()
        }
        subagents = [
            {
                "name": subagent.name,
                "description": subagent.description,
                "system_prompt": self._subagent_prompt(subagent, catalog),
                "tools": [tools[tool_name] for tool_name in subagent.tools],
                "model": model,
            }
            for subagent in catalog.enabled_subagents
        ]
        graph = self._agent_factory(
            model=model,
            tools=[],
            system_prompt=self._supervisor_prompt(
                state, rag_context, memory_context, catalog
            ),
            subagents=subagents,
            name="doorhandlecatch-harness-agent",
        )
        result = await graph.ainvoke(
            {"messages": [{"role": "user", "content": query}]},
            config={"recursion_limit": self._settings.deep_agent_max_iterations},
        )
        if evidence.pending_approval is not None:
            return self._pending_tool_approval_state(state, evidence.pending_approval)
        content = self._last_answer(result)
        if not content:
            raise RuntimeError("Harness Deep Agent 未返回有效回答")
        if not evidence.successful_calls:
            raise RuntimeError("Harness Deep Agent 未调用可信业务工具，拒绝无证据回答")
        return self._complete_state(
            state, content, rag_context, memories, degraded, evidence
        )

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
            model=self._settings.deep_agent_model,
            api_key=self._settings.deepseek_api_key,
            api_base=self._settings.deepseek_base_url,
            temperature=0,
            timeout=self._settings.deep_agent_model_timeout_seconds,
            max_retries=self._settings.deep_agent_model_max_retries,
        )

    def _build_query_tool(
        self,
        tool_name: str,
        definition: ToolDefinition,
        state: AgentState,
        rag_context: str,
        memory_context: str,
        evidence: ToolEvidence,
    ):
        @tool(tool_name, description=definition.description)
        async def query_business_data(question: str) -> str:
            """查询当前租户用户有权访问的实时业务数据。"""
            question = question.strip()
            if not question or len(question) > 4000:
                raise RuntimeError("业务工具问题不能为空且不得超过 4000 字符")
            policy = definition.human_intervention
            if policy.required:
                if evidence.pending_approval is None:
                    evidence.pending_approval = {
                        "tool_name": tool_name,
                        "target_agent": definition.target_agent,
                        "question": question,
                        "definition_checksum": self._tool_definition_checksum(definition),
                        "approval_message": policy.approval_message or "请确认是否执行该工具。",
                        "risk_level": policy.risk_level,
                    }
                return json.dumps(
                    {
                        "status": "PENDING_HUMAN_APPROVAL",
                        "tool": tool_name,
                        "message": policy.approval_message,
                    },
                    ensure_ascii=False,
                )
            result = await self._execute_query_tool(
                tool_name,
                definition,
                state,
                question,
                rag_context,
                memory_context,
                evidence,
            )
            return json.dumps(result, ensure_ascii=False)[:12000]

        return query_business_data

    async def _execute_query_tool(
        self,
        tool_name: str,
        definition: ToolDefinition,
        state: AgentState,
        question: str,
        rag_context: str,
        memory_context: str,
        evidence: ToolEvidence,
    ) -> dict[str, Any]:
        agent = definition.target_agent
        original = str(state.get("user_input", ""))
        prompt_parts = [f"原始用户问题：{original}", f"本专家需要解决：{question}"]
        if rag_context:
            prompt_parts.append(
                "系统知识库参考（实时状态以业务数据为准）：\n" + rag_context
            )
        if memory_context:
            prompt_parts.append("当前用户历史记忆：\n" + memory_context)
        digest = hashlib.sha256(question.encode("utf-8")).hexdigest()[:12]
        try:
            result = await self._tool_client.execute(
                agent,
                definition.operation,
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
        except Exception:
            evidence.failed_agents.append(agent)
            raise
        content = result.get("content") or result.get("message")
        if content is None or not str(content).strip():
            evidence.failed_agents.append(agent)
            raise RuntimeError(f"{agent} 业务工具返回空内容")
        evidence.successful_calls.append(
            {"agent": agent, "tool": tool_name, "questionHash": digest}
        )
        return result

    async def resume_tool(self, state: AgentState) -> AgentState:
        pending = state.get("pending_tool_approval")
        if not isinstance(pending, dict):
            raise RuntimeError("不存在待人工确认的工具调用")
        if not state.get("confirmed"):
            raise RuntimeError("工具调用尚未获得人工确认")
        action_id = str(pending.get("action_id", ""))
        if not action_id or action_id != str(state.get("resume_action_id", "")):
            raise RuntimeError("待确认工具动作编号不一致")

        catalog = self._config_loader.load()
        tool_name = str(pending.get("tool_name", ""))
        definition = catalog.tools.get(tool_name)
        if definition is None:
            raise RuntimeError("待确认工具已不在当前 YAML 配置中")
        if self._tool_definition_checksum(definition) != pending.get(
            "definition_checksum"
        ):
            raise RuntimeError("待确认工具配置已变化，请重新发起请求")
        if not definition.human_intervention.required:
            raise RuntimeError("待确认工具的人工介入策略已变化，请重新发起请求")

        evidence = ToolEvidence()
        result = await self._execute_query_tool(
            tool_name,
            definition,
            state,
            str(pending.get("question", "")),
            "",
            "",
            evidence,
        )
        content = result.get("content") or result.get("message")
        return self._complete_approved_tool_state(
            state,
            str(content),
            str(result.get("resultType", "TEXT")),
            evidence,
        )

    @staticmethod
    def _tool_definition_checksum(definition: ToolDefinition) -> str:
        canonical = json.dumps(
            definition.model_dump(mode="json"),
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        return hashlib.sha256(canonical).hexdigest()

    def _pending_tool_approval_state(
        self, state: AgentState, pending: dict[str, str]
    ) -> AgentState:
        result = AgentState(**state)
        action_id = str(uuid.uuid4())
        route = keyword_route(str(state.get("user_input", "")))
        preview = pending["approval_message"]
        turn = int(state.get("turn", 0)) + 1
        recent = list(state.get("recent_msgs", []))
        recent.extend(
            [
                {"role": "user", "content": state.get("user_input", ""), "turn": turn},
                {"role": "assistant", "content": preview, "turn": turn},
            ]
        )
        persisted_pending = {
            **pending,
            "action_id": action_id,
        }
        result.update(
            turn=turn,
            route_decision=route,
            intent=route["intent"],
            pending_action_id=action_id,
            pending_tool_approval=persisted_pending,
            action={
                "action_id": action_id,
                "intent": route["intent"],
                "target_agent": pending["target_agent"],
                "preview": preview,
                "task_prompt": str(state.get("user_input", "")),
                "parameters": {
                    "toolName": pending["tool_name"],
                    "riskLevel": pending["risk_level"],
                },
            },
            result_content=preview,
            result_type="PENDING_ACTION",
            phase="AWAITING_TOOL_APPROVAL",
            exit_reason="PENDING_CONFIRMATION",
            current_node="human_intervention",
            node_trace=(
                list(state.get("node_trace", []))
                + ["context", "harness_deep_agent", "human_intervention"]
            )[-self._settings.max_trace_size :],
            recent_msgs=recent[-20:],
        )
        return result

    def _complete_approved_tool_state(
        self,
        state: AgentState,
        content: str,
        result_type: str,
        evidence: ToolEvidence,
    ) -> AgentState:
        result = AgentState(**state)
        recent = list(state.get("recent_msgs", []))
        recent.append(
            {
                "role": "assistant",
                "content": content,
                "turn": int(state.get("turn", 0)),
            }
        )
        trace = list(state.get("node_trace", [])) + [
            "human_intervention_resume",
            "approved_tool",
            "deep_agent_quality_gate",
        ]
        result.update(
            confirmed=False,
            pending_action_id="",
            pending_tool_approval={},
            action={},
            data_context={"deepAgentEvidence": evidence.successful_calls},
            result_content=content,
            result_type=result_type,
            phase="RESPONDING",
            exit_reason="COMPLETE",
            current_node="approved_tool",
            node_trace=trace[-self._settings.max_trace_size :],
            recent_msgs=recent[-20:],
        )
        return result

    @staticmethod
    def _subagent_prompt(
        subagent: SubagentDefinition, catalog: SubagentCatalog
    ) -> str:
        responsibilities = "\n".join(
            f"- {item}" for item in subagent.responsibilities
        )
        skill_sections = []
        for skill_name in subagent.skills:
            skill = catalog.skills[skill_name]
            instructions = "\n".join(f"  - {item}" for item in skill.instructions)
            skill_sections.append(
                f"- {skill_name}：{skill.description}\n{instructions}"
            )
        allowed_tools = "、".join(subagent.tools)
        approval_tools = [
            name
            for name in subagent.tools
            if catalog.tools[name].human_intervention.required
        ]
        approval_rule = (
            f"调用 {'、'.join(approval_tools)} 前必须暂停并等待人工确认。"
            if approval_tools
            else "当前分配工具均为自动批准的低风险只读工具。"
        )
        return (
            f"你是 DoorHandleCatch Harness Agent 的{subagent.description}。\n\n"
            f"职责：\n{responsibilities}\n\n"
            f"已分配 Skills：\n{chr(10).join(skill_sections)}\n\n"
            f"只允许使用这些工具：{allowed_tools}。"
            f"{approval_rule}"
            "不得伪造数据，不得尝试修改状态，不得把任务再委派给其他智能体。"
            "必须根据工具结果整理为简洁、可核对的中文结论后返回主 Agent。"
        )

    @staticmethod
    def _supervisor_prompt(
        state: AgentState,
        rag_context: str,
        memory_context: str,
        catalog: SubagentCatalog,
    ) -> str:
        available_agents = "；".join(
            f"{item.name}（{item.description}）"
            for item in catalog.enabled_subagents
        )
        context_parts = [
            "你是 DoorHandleCatch 工业质检平台的 Harness 主 Agent。",
            "你负责分解用户目标、使用 write_todos 规划复杂任务，并通过 task 委派给 YAML 中启用的子智能体。",
            f"当前可委派子智能体：{available_agents}。",
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
        evidence: ToolEvidence,
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
        trace = list(state.get("node_trace", [])) + [
            "context",
            "harness_deep_agent",
            "deep_agent_quality_gate",
        ]
        result.update(
            turn=turn,
            route_decision=route,
            intent=route["intent"],
            rag_context=rag_context,
            user_memories=memories,
            user_memory_context=format_memories(memories),
            context_degraded=degraded,
            data_context={
                "deepAgentEvidence": evidence.successful_calls,
                "failedToolAgents": evidence.failed_agents,
            },
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
