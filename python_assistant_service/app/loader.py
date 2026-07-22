"""YAML 子智能体目录加载、合并与热更新。"""

from __future__ import annotations

import hashlib
import threading
from pathlib import Path

import yaml
from pydantic import ValidationError
from yaml.constructor import ConstructorError

from .agent_config import (
    AgentConfigError,
    SkillDefinition,
    SubagentCatalog,
    SubagentDocument,
    ToolDefinition,
)


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


class YamlSubagentLoader:
    """扫描一 Agent 一 YAML 的目录；文件变化时原子切换配置。"""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path).expanduser().resolve()
        self._lock = threading.RLock()
        self._digest: str | None = None
        self._catalog: SubagentCatalog | None = None

    def load(self) -> SubagentCatalog:
        """加载全部配置；目录内容未变化时返回同一个只读目录对象。"""
        with self._lock:
            files = self._config_files()
            contents = self._read_files(files)
            digest = self._content_digest(contents)
            if digest == self._digest and self._catalog is not None:
                return self._catalog

            catalog = self._build_catalog(files, contents)
            self._catalog = catalog
            self._digest = digest
            return catalog

    def _read_files(self, files: tuple[Path, ...]) -> tuple[tuple[Path, bytes], ...]:
        try:
            contents = tuple((path, path.read_bytes()) for path in files)
        except OSError as exc:
            raise AgentConfigError(f"子智能体配置目录不可读: {self.path}") from exc
        if any(len(content) > 64 * 1024 for _, content in contents):
            raise AgentConfigError("单个子智能体 YAML 超过 64 KiB 限制")
        if sum(len(content) for _, content in contents) > 256 * 1024:
            raise AgentConfigError("全部子智能体 YAML 超过 256 KiB 限制")
        return contents

    @staticmethod
    def _content_digest(contents: tuple[tuple[Path, bytes], ...]) -> str:
        digest_builder = hashlib.sha256()
        for path, content in contents:
            encoded_name = path.name.encode("utf-8")
            digest_builder.update(len(encoded_name).to_bytes(2, "big"))
            digest_builder.update(encoded_name)
            digest_builder.update(len(content).to_bytes(8, "big"))
            digest_builder.update(content)
        return digest_builder.hexdigest()

    def _build_catalog(
        self,
        files: tuple[Path, ...],
        contents: tuple[tuple[Path, bytes], ...],
    ) -> SubagentCatalog:
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
            return SubagentCatalog(
                version=1,
                skills=skills,
                tools=tools,
                subagents=tuple(document.subagent for document in documents),
            )
        except AgentConfigError:
            raise
        except (yaml.YAMLError, ValidationError, ValueError) as exc:
            raise AgentConfigError(f"子智能体 YAML 目录校验失败: {exc}") from exc

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
