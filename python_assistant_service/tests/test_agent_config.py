from __future__ import annotations

import textwrap
from pathlib import Path

import pytest

from python_assistant_service.app.agent_config import AgentConfigError, YamlSubagentLoader


PROJECT_CONFIG = Path("python_assistant_service/config/subagents")


def write_config(
    directory,
    *,
    agent_id="DETECTION",
    name="detection-specialist",
    description="检测专家",
    tool="query_detection",
    target_agent="DETECTION",
    enabled="true",
    approval_required="false",
    risk_level="low",
    approval_message="",
    filename=None,
):
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / (filename or f"{name}.yaml")
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
                target_agent: {target_agent}
                operation: query
                description: 查询检测数据。
                human_intervention:
                  required: {approval_required}
                  timing: before_tool
                  risk_level: {risk_level}
            {approval_message}
            subagent:
              id: {agent_id}
              name: {name}
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
    return path


def test_project_uses_one_yaml_file_for_each_subagent():
    catalog = YamlSubagentLoader(PROJECT_CONFIG).load()

    assert {path.stem for path in PROJECT_CONFIG.glob("*.yaml")} == {
        item.name for item in catalog.subagents
    }
    assert {item.id for item in catalog.enabled_subagents} == {
        "DETECTION",
        "RESOURCE",
        "REPORT",
        "OPS",
    }
    assert catalog.tools["query_detection"].human_intervention.required is False
    assert catalog.tools["query_ops"].human_intervention.required is True
    assert catalog.tools["query_ops"].human_intervention.timing == "before_tool"
    assert catalog.tools["query_ops"].human_intervention.risk_level == "high"


def test_yaml_directory_merges_agent_responsibilities_skills_and_tools(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory)

    catalog = YamlSubagentLoader(directory).load()
    agent = catalog.enabled_subagents[0]

    assert agent.id == "DETECTION"
    assert agent.responsibilities == ("查询检测任务和缺陷证据。",)
    assert agent.skills == ("inspect-task",)
    assert agent.tools == ("query_detection",)
    assert catalog.skills["inspect-task"].instructions == ("必须依据工具数据回答。",)


def test_yaml_loader_hot_reloads_one_changed_agent_file(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory, description="检测专家")
    loader = YamlSubagentLoader(directory)

    first = loader.load()
    write_config(directory, description="高级检测专家")
    second = loader.load()

    assert first.enabled_subagents[0].description == "检测专家"
    assert second.enabled_subagents[0].description == "高级检测专家"
    assert second is not first


def test_disabled_subagent_is_not_exposed_to_supervisor_catalog(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory)
    write_config(
        directory,
        agent_id="QUALITY_AUDIT",
        name="quality-audit-specialist",
        enabled="false",
    )

    catalog = YamlSubagentLoader(directory).load()

    assert len(catalog.subagents) == 2
    assert [item.id for item in catalog.enabled_subagents] == ["DETECTION"]


def test_new_subagent_can_reuse_identical_skill_and_trusted_tool_definitions(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory)
    write_config(
        directory,
        agent_id="QUALITY_REVIEW",
        name="quality-review-specialist",
    )

    catalog = YamlSubagentLoader(directory).load()

    assert {item.id for item in catalog.enabled_subagents} == {
        "DETECTION",
        "QUALITY_REVIEW",
    }
    assert catalog.tools["query_detection"].target_agent == "DETECTION"


def test_conflicting_shared_skill_definition_is_rejected(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory)
    second = write_config(
        directory,
        agent_id="QUALITY_REVIEW",
        name="quality-review-specialist",
    )
    second.write_text(
        second.read_text(encoding="utf-8").replace(
            "description: 查询检测任务。", "description: 冲突的查询说明。"
        ),
        encoding="utf-8",
    )

    with pytest.raises(AgentConfigError, match="Skill.*定义冲突"):
        YamlSubagentLoader(directory).load()


def test_each_agent_file_must_define_its_own_referenced_skills_and_tools(tmp_path):
    directory = tmp_path / "subagents"
    path = write_config(directory)
    content = path.read_text(encoding="utf-8")
    content = content.replace(
        "skills:\n  inspect-task:\n    description: 查询检测任务。\n    instructions:\n      - 必须依据工具数据回答。\n",
        "skills: {}\n",
        1,
    )
    path.write_text(content, encoding="utf-8")

    with pytest.raises(AgentConfigError, match="当前文件未定义.*Skill"):
        YamlSubagentLoader(directory).load()


def test_yaml_cannot_reference_unregistered_python_tool(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory, tool="execute_shell")

    with pytest.raises(AgentConfigError, match="工具未在代码白名单注册"):
        YamlSubagentLoader(directory).load()


def test_yaml_cannot_forge_trusted_tool_backend_binding(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory, target_agent="RESOURCE")

    with pytest.raises(AgentConfigError, match="工具绑定与代码白名单不一致"):
        YamlSubagentLoader(directory).load()


def test_required_human_intervention_must_explain_approval(tmp_path):
    directory = tmp_path / "subagents"
    write_config(
        directory,
        approval_required="true",
        risk_level="high",
    )

    with pytest.raises(AgentConfigError, match="必须配置 approval_message"):
        YamlSubagentLoader(directory).load()


def test_high_risk_tool_cannot_disable_human_intervention(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory, risk_level="high", approval_required="false")

    with pytest.raises(AgentConfigError, match="high 风险工具必须启用"):
        YamlSubagentLoader(directory).load()


def test_yaml_requires_at_least_one_enabled_subagent(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory, enabled="false")

    with pytest.raises(AgentConfigError, match="至少需要启用一个子智能体"):
        YamlSubagentLoader(directory).load()


def test_filename_must_match_subagent_name(tmp_path):
    directory = tmp_path / "subagents"
    write_config(directory, filename="wrong-name.yaml")

    with pytest.raises(AgentConfigError, match="文件名必须与子智能体 name 一致"):
        YamlSubagentLoader(directory).load()


def test_yaml_rejects_duplicate_keys_and_aliases(tmp_path):
    duplicate_dir = tmp_path / "duplicate"
    duplicate = write_config(duplicate_dir)
    duplicate.write_text(
        duplicate.read_text(encoding="utf-8").replace(
            "version: 1", "version: 1\nversion: 1"
        ),
        encoding="utf-8",
    )
    with pytest.raises(AgentConfigError, match="duplicate key"):
        YamlSubagentLoader(duplicate_dir).load()

    alias_dir = tmp_path / "alias"
    alias_dir.mkdir()
    (alias_dir / "alias-agent.yaml").write_text(
        "version: &version 1\ncopy: *version\n", encoding="utf-8"
    )
    with pytest.raises(AgentConfigError, match="不允许锚点或别名"):
        YamlSubagentLoader(alias_dir).load()
