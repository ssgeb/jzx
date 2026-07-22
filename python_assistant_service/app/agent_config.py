"""受信 YAML 子智能体配置的严格模型与热加载器。"""

from __future__ import annotations

import hashlib
import re
import threading
from pathlib import Path

import yaml
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    ValidationError,
    ValidationInfo,
    field_validator,
    model_validator,
)
from yaml.constructor import ConstructorError


NAME_PATTERN = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
AGENT_ID_PATTERN = re.compile(r"[A-Z][A-Z0-9_]{1,31}")
TOOL_NAME_PATTERN = re.compile(r"[a-z][a-z0-9_]{2,63}")
FORBIDDEN_TOOL_NAMES = frozenset(
    {"ls", "read_file", "write_file", "edit_file", "glob", "grep", "execute"}
)

# YAML 只能引用这里已经实现并经过审查的业务工具，不能导入任意 Python 函数。
TRUSTED_TOOL_BINDINGS: dict[str, tuple[str, str]] = {
    "query_detection": ("DETECTION", "query"),
    "query_resource": ("RESOURCE", "query"),
    "query_report": ("REPORT", "query"),
    "query_ops": ("OPS", "query"),
}


class AgentConfigError(RuntimeError):
    pass


class _UniqueKeySafeLoader(yaml.SafeLoader):
    """安全加载 YAML，并把重复键视为配置错误。"""


def _construct_unique_mapping(loader, node, deep=False):
    mapping = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                f"found duplicate key ({key})",
                key_node.start_mark,
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


_UniqueKeySafeLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    _construct_unique_mapping,
)


class SkillDefinition(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    description: str = Field(min_length=1, max_length=500)
    instructions: tuple[str, ...] = Field(min_length=1, max_length=20)

    @field_validator("description")
    @classmethod
    def description_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("Skill description 不能为空白")
        return value.strip()

    @field_validator("instructions")
    @classmethod
    def instructions_must_be_concise(cls, values: tuple[str, ...]) -> tuple[str, ...]:
        normalized = tuple(item.strip() for item in values)
        if any(not item or len(item) > 500 for item in normalized):
            raise ValueError("Skill instruction 不能为空且不得超过 500 字符")
        return normalized


class ToolDefinition(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    target_agent: str
    operation: str = "query"
    description: str = Field(min_length=1, max_length=500)

    @field_validator("target_agent")
    @classmethod
    def target_agent_must_be_valid(cls, value: str) -> str:
        normalized = value.strip().upper()
        if not AGENT_ID_PATTERN.fullmatch(normalized):
            raise ValueError("target_agent 格式无效")
        return normalized

    @field_validator("operation")
    @classmethod
    def only_query_is_allowed(cls, value: str) -> str:
        normalized = value.strip().lower()
        if normalized != "query":
            raise ValueError("YAML 子智能体当前只允许 query 只读操作")
        return normalized

    @field_validator("description")
    @classmethod
    def description_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("工具 description 不能为空白")
        return value.strip()


class SubagentDefinition(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str
    name: str
    enabled: bool = True
    description: str = Field(min_length=1, max_length=500)
    responsibilities: tuple[str, ...] = Field(min_length=1, max_length=20)
    skills: tuple[str, ...] = Field(min_length=1, max_length=20)
    tools: tuple[str, ...] = Field(min_length=1, max_length=10)

    @field_validator("id")
    @classmethod
    def id_must_be_valid(cls, value: str) -> str:
        normalized = value.strip().upper()
        if not AGENT_ID_PATTERN.fullmatch(normalized):
            raise ValueError("子智能体 id 格式无效")
        return normalized

    @field_validator("name")
    @classmethod
    def name_must_be_valid(cls, value: str) -> str:
        normalized = value.strip().lower()
        if not NAME_PATTERN.fullmatch(normalized):
            raise ValueError("子智能体 name 必须使用小写字母、数字和连字符")
        return normalized

    @field_validator("responsibilities", "skills", "tools")
    @classmethod
    def list_values_must_be_unique(
        cls, values: tuple[str, ...], info: ValidationInfo
    ) -> tuple[str, ...]:
        normalized = tuple(item.strip() for item in values)
        if any(not item for item in normalized) or len(set(normalized)) != len(normalized):
            raise ValueError("子智能体列表项不能为空或重复")
        if info.field_name == "responsibilities" and any(
            len(item) > 500 for item in normalized
        ):
            raise ValueError("单条子智能体职责不得超过 500 字符")
        return normalized

    @field_validator("description")
    @classmethod
    def description_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("子智能体 description 不能为空白")
        return value.strip()


class SubagentDocument(BaseModel):
    """一个 YAML 文件只定义一个子智能体及其依赖。"""

    model_config = ConfigDict(extra="forbid", frozen=True)

    version: int = Field(ge=1, le=1)
    skills: dict[str, SkillDefinition]
    tools: dict[str, ToolDefinition]
    subagent: SubagentDefinition

    @model_validator(mode="after")
    def subagent_dependencies_must_be_local(self) -> "SubagentDocument":
        missing_skills = set(self.subagent.skills) - self.skills.keys()
        missing_tools = set(self.subagent.tools) - self.tools.keys()
        if missing_skills:
            raise ValueError(
                f"当前文件未定义子智能体引用的 Skill: {sorted(missing_skills)}"
            )
        if missing_tools:
            raise ValueError(
                f"当前文件未定义子智能体引用的工具: {sorted(missing_tools)}"
            )
        return self


class SubagentCatalog(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    version: int = Field(ge=1, le=1)
    skills: dict[str, SkillDefinition]
    tools: dict[str, ToolDefinition]
    subagents: tuple[SubagentDefinition, ...] = Field(min_length=1, max_length=32)

    @model_validator(mode="after")
    def references_must_be_safe_and_complete(self) -> "SubagentCatalog":
        for skill_name in self.skills:
            if not NAME_PATTERN.fullmatch(skill_name):
                raise ValueError(f"Skill 名称格式无效: {skill_name}")
        for tool_name, definition in self.tools.items():
            if not TOOL_NAME_PATTERN.fullmatch(tool_name):
                raise ValueError(f"工具名称格式无效: {tool_name}")
            if tool_name in FORBIDDEN_TOOL_NAMES:
                raise ValueError(f"禁止在子智能体中配置文件或执行工具: {tool_name}")
            trusted = TRUSTED_TOOL_BINDINGS.get(tool_name)
            if trusted is None:
                raise ValueError(f"工具未在代码白名单注册: {tool_name}")
            if trusted != (definition.target_agent, definition.operation):
                raise ValueError(f"工具绑定与代码白名单不一致: {tool_name}")

        ids = [item.id for item in self.subagents]
        names = [item.name for item in self.subagents]
        if len(ids) != len(set(ids)) or len(names) != len(set(names)):
            raise ValueError("子智能体 id 和 name 不能重复")
        if not any(item.enabled for item in self.subagents):
            raise ValueError("至少需要启用一个子智能体")

        for subagent in self.subagents:
            missing_skills = set(subagent.skills) - self.skills.keys()
            missing_tools = set(subagent.tools) - self.tools.keys()
            if missing_skills:
                raise ValueError(
                    f"子智能体 {subagent.id} 引用了未定义 Skill: {sorted(missing_skills)}"
                )
            if missing_tools:
                raise ValueError(
                    f"子智能体 {subagent.id} 引用了未定义工具: {sorted(missing_tools)}"
                )
        return self

    @property
    def enabled_subagents(self) -> tuple[SubagentDefinition, ...]:
        return tuple(item for item in self.subagents if item.enabled)


class YamlSubagentLoader:
    """扫描一 Agent 一 YAML 的目录；文件变化时原子切换配置。"""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path).expanduser().resolve()
        self._lock = threading.RLock()
        self._digest: str | None = None
        self._catalog: SubagentCatalog | None = None

    def load(self) -> SubagentCatalog:
        with self._lock:
            files = self._config_files()
            try:
                contents = [(path, path.read_bytes()) for path in files]
            except OSError as exc:
                raise AgentConfigError(f"子智能体配置目录不可读: {self.path}") from exc
            if any(len(content) > 64 * 1024 for _, content in contents):
                raise AgentConfigError("单个子智能体 YAML 超过 64 KiB 限制")
            if sum(len(content) for _, content in contents) > 256 * 1024:
                raise AgentConfigError("全部子智能体 YAML 超过 256 KiB 限制")
            digest_builder = hashlib.sha256()
            for path, content in contents:
                encoded_name = path.name.encode("utf-8")
                digest_builder.update(len(encoded_name).to_bytes(2, "big"))
                digest_builder.update(encoded_name)
                digest_builder.update(len(content).to_bytes(8, "big"))
                digest_builder.update(content)
            digest = digest_builder.hexdigest()
            if digest == self._digest and self._catalog is not None:
                return self._catalog
            try:
                documents = [
                    self._parse_document(path, content) for path, content in contents
                ]
                skills: dict[str, SkillDefinition] = {}
                tools: dict[str, ToolDefinition] = {}
                for path, document in zip(files, documents, strict=True):
                    if path.stem != document.subagent.name:
                        raise AgentConfigError(
                            f"文件名必须与子智能体 name 一致: {path.name}"
                        )
                    self._merge_definitions(skills, document.skills, "Skill", path)
                    self._merge_definitions(tools, document.tools, "工具", path)
                catalog = SubagentCatalog(
                    version=1,
                    skills=skills,
                    tools=tools,
                    subagents=tuple(document.subagent for document in documents),
                )
            except AgentConfigError:
                raise
            except (yaml.YAMLError, ValidationError, ValueError) as exc:
                raise AgentConfigError(f"子智能体 YAML 目录校验失败: {exc}") from exc
            self._catalog = catalog
            self._digest = digest
            return catalog

    def _config_files(self) -> tuple[Path, ...]:
        if not self.path.is_dir():
            raise AgentConfigError(f"子智能体配置路径必须是目录: {self.path}")
        try:
            files = tuple(sorted(self.path.glob("*.yaml"), key=lambda item: item.name))
        except OSError as exc:
            raise AgentConfigError(f"子智能体配置目录不可读: {self.path}") from exc
        if not files:
            raise AgentConfigError("子智能体配置目录中没有 YAML 文件")
        if len(files) > 32:
            raise AgentConfigError("子智能体 YAML 文件数量超过 32 个限制")
        if any(path.is_symlink() or not path.is_file() for path in files):
            raise AgentConfigError("子智能体配置不允许符号链接或非普通文件")
        return files

    @staticmethod
    def _parse_document(path: Path, content: bytes) -> SubagentDocument:
        try:
            events = yaml.parse(content, Loader=_UniqueKeySafeLoader)
            if any(
                isinstance(event, yaml.events.AliasEvent)
                or getattr(event, "anchor", None) is not None
                for event in events
            ):
                raise AgentConfigError(f"子智能体 YAML 不允许锚点或别名: {path.name}")
            raw = yaml.load(content, Loader=_UniqueKeySafeLoader)
            return SubagentDocument.model_validate(raw)
        except AgentConfigError:
            raise
        except (yaml.YAMLError, ValidationError, ValueError) as exc:
            raise AgentConfigError(f"子智能体 YAML 校验失败 ({path.name}): {exc}") from exc

    @staticmethod
    def _merge_definitions(target: dict, incoming: dict, kind: str, path: Path) -> None:
        for name, definition in incoming.items():
            existing = target.get(name)
            if existing is not None and existing != definition:
                raise AgentConfigError(
                    f"{kind} 在多个子智能体文件中的定义冲突: {name} ({path.name})"
                )
            target[name] = definition
