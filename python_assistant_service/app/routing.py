"""确定性路由、槽位提取和模型结果校验。"""

from __future__ import annotations

import re
from typing import Any


AGENTS = {"DETECTION", "RESOURCE", "REPORT", "OPS"}
MULTI_TURN_INTENTS = {"NEW_TASK", "SUPPLEMENT", "MODIFY", "FOLLOWUP", "CHITCHAT"}
REQUIRED_SLOTS = {
    "IMAGE_DETECTION_UPLOAD": {"folderPath"},
    "IMAGE_DETECTION_START": {"taskId"},
}


def _contains(text: str, *keywords: str) -> bool:
    return any(keyword in text for keyword in keywords)


def has_write_intent(text: str) -> bool:
    if _contains(text, "确认缺陷数", "确认缺陷率", "确认缺陷数量"):
        return False
    return _contains(
        text,
        "开始", "启动", "发起", "创建", "新建", "新增", "修改", "更新", "删除",
        "重试", "上传", "标记", "置为", "设为", "改为", "提交", "处置", "放行", "报废",
    )


def keyword_route(content: str) -> dict[str, Any]:
    text = (content or "").strip().lower()
    is_action = has_write_intent(text)
    if _contains(text, "在哪", "入口", "页面", "菜单", "怎么用", "业务地图", "功能地图", "导航", "下一步"):
        agent = "OPS"
        is_action = False
    elif _contains(text, "报表", "日报", "周报", "摘要", "统计", "趋势", "汇总", "工作量", "处置统计", "质检统计"):
        agent = "REPORT"
    elif _contains(text, "检测", "图片", "图像", "漏检", "结果图", "任务进度", "采集", "质检", "复核", "处置", "返工", "复检", "缺陷", "证据", "工单", "批次", "队列"):
        agent = "DETECTION"
    elif _contains(text, "设备", "人员", "员工", "模型", "评估指标", "灰度", "回滚", "发布", "默认模型", "在线状态", "采集告警"):
        agent = "RESOURCE"
    else:
        agent = "OPS"
        is_action = False
    return {
        "intent": f"{agent}_{'ACTION' if is_action else 'QUERY'}",
        "targetAgent": agent,
        "confirmationRequired": is_action,
        "normalizedUserPrompt": text,
        "multiTurnIntent": "NEW_TASK",
        "slotUpdates": extract_slots(text),
    }


def validated_model_route(content: str, model_result: dict[str, Any] | None) -> dict[str, Any] | None:
    if not model_result:
        return None
    agent = str(model_result.get("target_agent", "")).upper()
    multi_turn = str(model_result.get("intent", "")).upper()
    if agent not in AGENTS or multi_turn not in MULTI_TURN_INTENTS:
        return None
    is_action = bool(model_result.get("is_action", False))
    slot_updates = model_result.get("slot_updates")
    if not isinstance(slot_updates, dict):
        slot_updates = {}
    return {
        "intent": f"{agent}_{'ACTION' if is_action else 'QUERY'}",
        "targetAgent": agent,
        "confirmationRequired": is_action,
        "normalizedUserPrompt": content.strip().lower(),
        "multiTurnIntent": multi_turn,
        "slotUpdates": slot_updates,
    }


def extract_slots(text: str) -> dict[str, Any]:
    slots: dict[str, Any] = {}
    task_match = re.search(r"(?:任务|task)\s*(?:id|编号)?\s*[:：#]?\s*([a-z0-9_-]{3,})", text, re.I)
    if task_match:
        slots["taskId"] = task_match.group(1)
    path_match = re.search(r"([a-z]:[\\/][^\s，。；]+|/[^\s，。；]+)", text, re.I)
    if path_match:
        slots["folderPath"] = path_match.group(1)
    return slots


def resolve_task_type(content: str, route: dict[str, Any], slots: dict[str, Any]) -> str | None:
    if route["targetAgent"] != "DETECTION" or not route["confirmationRequired"]:
        return None
    if "taskId" in slots or _contains(content, "开始检测", "启动检测", "执行检测"):
        return "IMAGE_DETECTION_START"
    if "上传" in content:
        return "IMAGE_DETECTION_UPLOAD"
    return None
