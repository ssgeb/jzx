"""LangGraph 主/子 Agent 编排。"""

from __future__ import annotations

import json
import uuid
from typing import Any, Awaitable, Callable, Literal

from langgraph.graph import END, START, StateGraph

from .clients import IntentModel, ToolClient
from .routing import REQUIRED_SLOTS, extract_slots, keyword_route, resolve_task_type, validated_model_route
from .settings import Settings
from .state import AgentState


Node = Callable[[AgentState], Awaitable[dict[str, Any]]]


def _trace_update(state: AgentState, node: str, max_size: int) -> dict[str, Any]:
    trace = list(state.get("node_trace", []))
    trace.append(node)
    visits = dict(state.get("node_visit_count", {}))
    visits[node] = visits.get(node, 0) + 1
    return {
        "current_node": node,
        "node_trace": trace[-max_size:],
        "node_visit_count": visits,
        "iteration": int(state.get("iteration", 0)) + 1,
    }


class AgentGraph:
    def __init__(self, settings: Settings, tool_client: ToolClient, model: IntentModel) -> None:
        self._settings = settings
        self._tool_client = tool_client
        self._model = model
        self._graph = self._build_message_graph()
        self._resume_graph = self._build_resume_graph()

    async def invoke(self, state: AgentState) -> AgentState:
        result = await self._graph.ainvoke(
            state, config={"recursion_limit": self._settings.max_graph_iterations}
        )
        return AgentState(**result)

    async def resume(self, state: AgentState) -> AgentState:
        result = await self._resume_graph.ainvoke(
            state, config={"recursion_limit": self._settings.max_graph_iterations}
        )
        return AgentState(**result)

    def _with_trace(self, name: str, node: Node) -> Node:
        async def wrapped(state: AgentState) -> dict[str, Any]:
            update = _trace_update(state, name, self._settings.max_trace_size)
            if update["node_visit_count"][name] > 4:
                return {**update, "error": f"节点 {name} 执行次数超过限制"}
            try:
                return {**update, **(await node(state))}
            except Exception as exc:  # 节点异常统一进入 fallback
                return {**update, "error": f"{name}: {exc}"}

        return wrapped

    async def _router(self, state: AgentState) -> dict[str, Any]:
        content = state.get("user_input", "")
        context = {
            "turn": state.get("turn", 0),
            "summary": state.get("summary", ""),
            "recent_msgs": state.get("recent_msgs", []),
            "slots": state.get("slots", {}),
            "task_type": state.get("task_type"),
        }
        model_result = None
        try:
            model_result = await self._model.classify(content, context)
        except Exception:
            # 模型路由失败属于可降级故障，继续使用确定性关键词路由。
            model_result = None
        model_route = validated_model_route(content, model_result)
        route = model_route or keyword_route(content)
        slots = dict(state.get("slots", {}))
        slot_updates = route.pop("slotUpdates", {})
        slot_updates.update(extract_slots(content))
        slots.update(slot_updates)

        previous_task_type = state.get("task_type", "")
        previous_route = state.get("route_decision", {})
        is_supplement = (
            state.get("phase") == "COLLECTING"
            and bool(previous_task_type)
            and bool(previous_route)
            and (
                bool(slot_updates)
                or (model_route or {}).get("multiTurnIntent") in {"SUPPLEMENT", "MODIFY"}
            )
        )
        if is_supplement:
            route = dict(previous_route)
            task_type = previous_task_type
            task_prompt = state.get("task_prompt", content)
        else:
            task_type = resolve_task_type(content.lower(), route, slots)
            task_prompt = content
        missing = sorted(REQUIRED_SLOTS.get(task_type, set()) - slots.keys())
        phase = "COLLECTING" if missing else "EXECUTING"
        return {
            "turn": int(state.get("turn", 0)) + 1,
            "route_decision": route,
            "intent": route["intent"],
            "slots": slots,
            "task_prompt": task_prompt,
            "task_type": task_type or "",
            "missing_slots": missing,
            "phase": phase,
        }

    async def _slot_filling(self, state: AgentState) -> dict[str, Any]:
        labels = {"folderPath": "图片文件夹路径", "taskId": "检测任务编号"}
        missing = state.get("missing_slots", [])
        readable = "、".join(labels.get(item, item) for item in missing)
        return {
            "result_content": f"还需要你补充：{readable}。",
            "result_type": "TEXT",
            "phase": "COLLECTING",
        }

    async def _human_confirm(self, state: AgentState) -> dict[str, Any]:
        route = state.get("route_decision", {})
        target = str(route.get("targetAgent", "OPS"))
        preview_names = {
            "DETECTION": "执行检测相关操作",
            "RESOURCE": "修改设备、人员或模型资源",
            "REPORT": "生成或提交报表",
        }
        action_id = str(uuid.uuid4())
        task_prompt = state.get("task_prompt") or state.get("user_input", "")
        preview = f"即将{preview_names.get(target, '执行业务操作')}：{task_prompt}。请确认后继续。"
        action = {
            "action_id": action_id,
            "intent": state.get("intent", ""),
            "target_agent": target,
            "preview": preview,
            "task_prompt": task_prompt,
            "parameters": dict(state.get("slots", {})),
        }
        turn = int(state.get("turn", 0))
        recent = list(state.get("recent_msgs", []))
        recent.extend([
            {"role": "user", "content": state.get("user_input", ""), "turn": turn},
            {"role": "assistant", "content": preview, "turn": turn},
        ])
        return {
            "pending_action_id": action_id,
            "action": action,
            "result_content": preview,
            "result_type": "PENDING_ACTION",
            "exit_reason": "PENDING_CONFIRMATION",
            "recent_msgs": recent[-20:],
        }

    def _agent_node(self, agent: str) -> Node:
        async def execute(state: AgentState) -> dict[str, Any]:
            is_action = bool(state.get("confirmed")) or state.get("intent", "").endswith("_ACTION")
            operation = "action" if is_action else "query"
            task_prompt = state.get("task_prompt") or state.get("user_input", "")
            slots = state.get("slots", {})
            tool_prompt = task_prompt
            if slots:
                tool_prompt += "\n已收集参数：" + json.dumps(slots, ensure_ascii=False)
            payload = {
                "requestId": state.get("request_id"),
                "tenantUserId": state.get("tenant_user_id"),
                "username": state.get("username"),
                "sessionId": state.get("thread_id"),
                "prompt": tool_prompt,
                "intent": state.get("intent", ""),
                "slots": slots,
                "currentRoute": state.get("current_route", ""),
                "currentPageTitle": state.get("current_page_title", ""),
            }
            result = await self._tool_client.execute(
                agent, operation, payload, state.get("idempotency_key", "")
            )
            content = result.get("content") or result.get("message")
            if content is None:
                content = json.dumps(result, ensure_ascii=False)
            return {
                "result_content": str(content),
                "result_type": str(result.get("resultType", "TEXT")),
                "data_context": result,
                "phase": "RESPONDING",
            }

        return execute

    async def _quality_gate(self, state: AgentState) -> dict[str, Any]:
        if state.get("error"):
            return {}
        content = state.get("result_content", "")
        result_type = state.get("result_type", "")
        if not content.strip():
            return {"error": "智能体返回内容为空"}
        if result_type not in {"TEXT", "BUSINESS_CARD", "PENDING_ACTION"}:
            return {"error": f"不支持的结果类型: {result_type}"}
        if not state.get("intent", "").strip():
            return {"error": "智能体结果缺少 intent"}
        return {}

    async def _responder(self, state: AgentState) -> dict[str, Any]:
        recent = list(state.get("recent_msgs", []))
        turn = int(state.get("turn", 0))
        if not state.get("confirmed"):
            recent.append({"role": "user", "content": state.get("user_input", ""), "turn": turn})
        recent.append({"role": "assistant", "content": state.get("result_content", ""), "turn": turn})
        return {
            "recent_msgs": recent[-20:],
            "exit_reason": "COMPLETE",
            "phase": "COLLECTING" if state.get("phase") == "COLLECTING" else "RESPONDING",
        }

    async def _fallback(self, state: AgentState) -> dict[str, Any]:
        return {
            "result_content": "智能助手暂时无法完成这次请求，请稍后重试。",
            "result_type": "TEXT",
            "exit_reason": "ERROR",
        }

    async def _resume_router(self, state: AgentState) -> dict[str, Any]:
        if not state.get("confirmed"):
            return {"error": "动作尚未确认"}
        if not state.get("pending_action_id") or state.get("pending_action_id") != state.get("resume_action_id"):
            return {"error": "待确认动作编号不一致"}
        return {"exit_reason": "", "phase": "EXECUTING"}

    @staticmethod
    def _route_after_router(state: AgentState) -> str:
        if state.get("error"):
            return "fallback"
        if state.get("phase") == "COLLECTING":
            return "slot_filling"
        route = state.get("route_decision", {})
        if route.get("confirmationRequired"):
            return "human_confirm"
        return f"{str(route.get('targetAgent', 'OPS')).lower()}_agent"

    @staticmethod
    def _route_after_quality(state: AgentState) -> Literal["fallback", "responder"]:
        return "fallback" if state.get("error") else "responder"

    @staticmethod
    def _route_after_resume(state: AgentState) -> str:
        if state.get("error"):
            return "fallback"
        route = state.get("route_decision", {})
        return f"{str(route.get('targetAgent', 'OPS')).lower()}_agent"

    def _register_common_nodes(self, builder: StateGraph) -> None:
        for agent in ("DETECTION", "RESOURCE", "REPORT", "OPS"):
            name = f"{agent.lower()}_agent"
            builder.add_node(name, self._with_trace(name, self._agent_node(agent)))
            builder.add_edge(name, "quality_gate")
        builder.add_node("quality_gate", self._with_trace("quality_gate", self._quality_gate))
        builder.add_node("responder", self._with_trace("responder", self._responder))
        builder.add_node("fallback", self._with_trace("fallback", self._fallback))
        builder.add_conditional_edges("quality_gate", self._route_after_quality)
        builder.add_edge("responder", END)
        builder.add_edge("fallback", END)

    def _build_message_graph(self):
        builder = StateGraph(AgentState)
        builder.add_node("router", self._with_trace("router", self._router))
        builder.add_node("slot_filling", self._with_trace("slot_filling", self._slot_filling))
        builder.add_node("human_confirm", self._with_trace("human_confirm", self._human_confirm))
        self._register_common_nodes(builder)
        builder.add_edge(START, "router")
        builder.add_conditional_edges(
            "router",
            self._route_after_router,
            {
                "fallback": "fallback",
                "slot_filling": "slot_filling",
                "human_confirm": "human_confirm",
                "detection_agent": "detection_agent",
                "resource_agent": "resource_agent",
                "report_agent": "report_agent",
                "ops_agent": "ops_agent",
            },
        )
        builder.add_edge("slot_filling", "quality_gate")
        builder.add_edge("human_confirm", END)
        return builder.compile()

    def _build_resume_graph(self):
        builder = StateGraph(AgentState)
        builder.add_node("resume_router", self._with_trace("resume_router", self._resume_router))
        self._register_common_nodes(builder)
        builder.add_edge(START, "resume_router")
        builder.add_conditional_edges(
            "resume_router",
            self._route_after_resume,
            {
                "fallback": "fallback",
                "detection_agent": "detection_agent",
                "resource_agent": "resource_agent",
                "report_agent": "report_agent",
                "ops_agent": "ops_agent",
            },
        )
        return builder.compile()
