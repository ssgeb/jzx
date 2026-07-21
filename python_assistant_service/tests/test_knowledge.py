from __future__ import annotations

import asyncio

from python_assistant_service.app.knowledge import LocalKnowledgeBase
from python_assistant_service.app.settings import Settings


def test_local_rag_retrieves_relevant_markdown_chunk(tmp_path):
    guide = tmp_path / "guide.md"
    guide.write_text(
        "# 设备告警\n设备离线告警出现后，应先检查采集网络和心跳状态。\n"
        "如果网络正常，再检查设备是否停机。\n\n"
        "# 报表\n日报用于汇总当天的检测数量和缺陷率。",
        encoding="utf-8",
    )
    settings = Settings(
        rag_sources=(str(guide),),
        rag_chunk_size=300,
        rag_top_k=1,
        rag_max_context_chars=1000,
    )
    knowledge = LocalKnowledgeBase(settings)

    context = asyncio.run(knowledge.retrieve("设备离线告警怎么处理"))

    assert knowledge.chunk_count == 2
    assert "设备告警" in context
    assert "检查采集网络和心跳状态" in context
    assert "日报用于" not in context


def test_disabled_rag_does_not_load_sources(tmp_path):
    guide = tmp_path / "guide.md"
    guide.write_text("# 文档\n这是一段足够长的知识库内容。", encoding="utf-8")
    knowledge = LocalKnowledgeBase(Settings(rag_enabled=False, rag_sources=(str(guide),)))

    assert knowledge.chunk_count == 0
    assert asyncio.run(knowledge.retrieve("知识库")) == ""
