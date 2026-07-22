from __future__ import annotations

import textwrap

import pytest

from python_assistant_service.app.agent_config import AgentConfigError, YamlSubagentLoader


def write_config(path, *, description="检测专家", tool="query_detection", enabled="true"):
    path.write_text(
        textwrap.dedent(
            f"""
            version: 1
            skills:
              inspect-task:
                description: 查询检测任务。
                instructions:
                  - 必须依据工具数据回答。
            tools:
              {tool}:
                target_agent: DETECTION
                operation: query
                description: 查询检测数据。
            subagents:
              - id: DETECTION
                name: detection-specialist
                enabled: {enabled}
                description: {description}
                responsibilities:
                  - 查询检测任务和缺陷证据。
                skills:
                  - inspect-task
                tools:
                  - {tool}
            """
        ).strip()
        + "\n",
        encoding="utf-8",
    )


def test_yaml_loader_reads_agent_responsibilities_skills_and_tools(tmp_path):
    path = tmp_path / "subagents.yaml"
    write_config(path)

    catalog = YamlSubagentLoader(path).load()
    agent = catalog.enabled_subagents[0]

    assert agent.id == "DETECTION"
    assert agent.responsibilities == ("查询检测任务和缺陷证据。",)
    assert agent.skills == ("inspect-task",)
    assert agent.tools == ("query_detection",)
    assert catalog.skills["inspect-task"].instructions == ("必须依据工具数据回答。",)


def test_yaml_loader_hot_reloads_changed_configuration(tmp_path):
    path = tmp_path / "subagents.yaml"
    write_config(path, description="检测专家")
    loader = YamlSubagentLoader(path)

    first = loader.load()
    write_config(path, description="高级检测专家")
    second = loader.load()

    assert first.enabled_subagents[0].description == "检测专家"
    assert second.enabled_subagents[0].description == "高级检测专家"
    assert second is not first


def test_disabled_subagent_is_not_exposed_to_supervisor_catalog(tmp_path):
    path = tmp_path / "subagents.yaml"
    write_config(path)
    extra = (
        "\n  - id: QUALITY_AUDIT\n"
        "    name: quality-audit-specialist\n"
        "    enabled: false\n"
        "    description: 质量审计专家\n"
        "    responsibilities:\n"
        "      - 审核检测证据。\n"
        "    skills:\n"
        "      - inspect-task\n"
        "    tools:\n"
        "      - query_detection\n"
    )
    path.write_text(path.read_text(encoding="utf-8") + extra, encoding="utf-8")

    catalog = YamlSubagentLoader(path).load()

    assert len(catalog.subagents) == 2
    assert [item.id for item in catalog.enabled_subagents] == ["DETECTION"]


def test_yaml_cannot_reference_unregistered_python_tool(tmp_path):
    path = tmp_path / "subagents.yaml"
    write_config(path, tool="execute_shell")

    with pytest.raises(AgentConfigError, match="工具未在代码白名单注册"):
        YamlSubagentLoader(path).load()


def test_new_subagent_can_compose_an_existing_trusted_tool(tmp_path):
    path = tmp_path / "subagents.yaml"
    write_config(path)
    content = path.read_text(encoding="utf-8").replace(
        "- id: DETECTION", "- id: QUALITY_REVIEW"
    )
    path.write_text(content, encoding="utf-8")

    catalog = YamlSubagentLoader(path).load()

    assert catalog.enabled_subagents[0].id == "QUALITY_REVIEW"
    assert catalog.enabled_subagents[0].tools == ("query_detection",)
    assert catalog.tools["query_detection"].target_agent == "DETECTION"


def test_yaml_cannot_forge_trusted_tool_backend_binding(tmp_path):
    path = tmp_path / "subagents.yaml"
    write_config(path)
    content = path.read_text(encoding="utf-8").replace(
        "target_agent: DETECTION", "target_agent: RESOURCE"
    )
    path.write_text(content, encoding="utf-8")

    with pytest.raises(AgentConfigError, match="工具绑定与代码白名单不一致"):
        YamlSubagentLoader(path).load()


def test_yaml_requires_at_least_one_enabled_subagent(tmp_path):
    path = tmp_path / "subagents.yaml"
    write_config(path, enabled="false")

    with pytest.raises(AgentConfigError, match="至少需要启用一个子智能体"):
        YamlSubagentLoader(path).load()


def test_yaml_rejects_duplicate_keys_and_aliases(tmp_path):
    duplicate = tmp_path / "duplicate.yaml"
    write_config(duplicate)
    duplicate.write_text(
        duplicate.read_text(encoding="utf-8").replace(
            "version: 1", "version: 1\nversion: 1"
        ),
        encoding="utf-8",
    )

    with pytest.raises(AgentConfigError, match="duplicate key"):
        YamlSubagentLoader(duplicate).load()

    alias = tmp_path / "alias.yaml"
    alias.write_text("version: &version 1\ncopy: *version\n", encoding="utf-8")
    with pytest.raises(AgentConfigError, match="不允许锚点或别名"):
        YamlSubagentLoader(alias).load()
