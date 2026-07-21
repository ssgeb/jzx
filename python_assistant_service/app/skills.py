"""从受信 GitHub 来源下载、校验并隔离安装 Harness Skills。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import shutil
import stat
import uuid
import zipfile
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import quote, urljoin, urlparse

import httpx
import yaml

from .schemas import SkillInstallRequest, SkillListResponse, SkillRecord
from .settings import Settings


SKILL_NAME_PATTERN = re.compile(r"[a-z0-9]+(?:-[a-z0-9]+)*")
DOWNLOAD_HOSTS = frozenset({"api.github.com", "codeload.github.com"})
REGISTRY_SCHEMA_VERSION = 1


class SkillError(RuntimeError):
    pass


class SkillCatalog:
    """Skill 下载仓库；下载内容默认隔离，绝不在安装阶段执行。"""

    def __init__(
        self,
        settings: Settings,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._settings = settings
        self._root = Path(settings.skills_root).expanduser().resolve()
        self._client = client or httpx.AsyncClient(
            timeout=httpx.Timeout(30.0, connect=5.0),
            follow_redirects=False,
            headers={"Accept": "application/vnd.github+json"},
        )
        self._owns_client = client is None
        self._lock = asyncio.Lock()

    async def list(self) -> SkillListResponse:
        async with self._lock:
            registry = self._read_registry()
            records = [
                SkillRecord.model_validate(item)
                for item in registry.get("skills", {}).values()
            ]
        records.sort(key=lambda item: (item.name, item.installed_at))
        return SkillListResponse(enabled=self._settings.skills_enabled, skills=records)

    async def install(self, request: SkillInstallRequest) -> SkillRecord:
        if not self._settings.skills_enabled:
            raise SkillError("Skill 下载功能未启用")
        if request.repository not in {
            item.lower() for item in self._settings.skill_allowed_repositories
        }:
            raise SkillError("Skill 仓库不在允许列表中")

        try:
            archive = await self._download_archive(request.repository, request.ref)
        except httpx.HTTPError as exc:
            raise SkillError("连接 GitHub 下载 Skill 失败") from exc
        async with self._lock:
            return self._install_archive(request, archive)

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def _download_archive(self, repository: str, ref: str) -> bytes:
        encoded_ref = quote(ref, safe="")
        url = f"https://api.github.com/repos/{repository}/zipball/{encoded_ref}"
        for _ in range(5):
            self._require_download_url(url)
            async with self._client.stream("GET", url) as response:
                if response.status_code in {301, 302, 303, 307, 308}:
                    location = response.headers.get("Location")
                    if not location:
                        raise SkillError("GitHub 下载重定向缺少目标地址")
                    url = urljoin(url, location)
                    continue
                try:
                    response.raise_for_status()
                except httpx.HTTPStatusError as exc:
                    raise SkillError(
                        f"GitHub 下载失败，状态码={response.status_code}"
                    ) from exc
                declared = response.headers.get("Content-Length")
                if declared:
                    try:
                        declared_size = int(declared)
                    except ValueError as exc:
                        raise SkillError("GitHub 返回了无效的内容长度") from exc
                    if declared_size > self._settings.skill_max_download_bytes:
                        raise SkillError("Skill 压缩包超过下载大小限制")
                chunks: list[bytes] = []
                total = 0
                async for chunk in response.aiter_bytes():
                    total += len(chunk)
                    if total > self._settings.skill_max_download_bytes:
                        raise SkillError("Skill 压缩包超过下载大小限制")
                    chunks.append(chunk)
                return b"".join(chunks)
        raise SkillError("GitHub 下载重定向次数过多")

    @staticmethod
    def _require_download_url(url: str) -> None:
        parsed = urlparse(url)
        if parsed.scheme != "https" or parsed.hostname not in DOWNLOAD_HOSTS:
            raise SkillError("Skill 下载地址不在 GitHub HTTPS 主机允许列表中")
        if parsed.username or parsed.password or parsed.port not in {None, 443}:
            raise SkillError("Skill 下载地址包含不安全的认证信息或端口")

    def _install_archive(
        self, request: SkillInstallRequest, archive: bytes
    ) -> SkillRecord:
        self._root.mkdir(parents=True, exist_ok=True)
        staging = self._root / f".staging-{uuid.uuid4().hex}"
        try:
            staging.mkdir(mode=0o700)
            extracted = self._extract_selected_skill(archive, request.path, staging)
            name, description = self._validate_skill(extracted, request.path)
            destination = self._root / name
            registry = self._read_registry()
            if destination.exists() or name in registry["skills"]:
                raise SkillError(f"Skill 已存在: {name}")
            checksum = self._content_checksum(extracted)
            record = SkillRecord(
                name=name,
                description=description,
                repository=request.repository,
                source_path=request.path,
                ref=request.ref,
                checksum=checksum,
                status="QUARANTINED",
                installed_at=datetime.now(timezone.utc).isoformat(),
                installed_by=request.requested_by,
            )
            os.replace(extracted, destination)
            try:
                registry.setdefault("skills", {})[name] = record.model_dump()
                self._write_registry(registry)
            except Exception:
                shutil.rmtree(destination, ignore_errors=True)
                raise
            return record
        except zipfile.BadZipFile as exc:
            raise SkillError("下载内容不是有效 ZIP 压缩包") from exc
        finally:
            shutil.rmtree(staging, ignore_errors=True)

    def _extract_selected_skill(
        self, archive: bytes, source_path: str, staging: Path
    ) -> Path:
        selected = PurePosixPath(source_path)
        output = staging / "skill"
        output.mkdir()
        file_count = 0
        total_size = 0
        found = False
        with zipfile.ZipFile(BytesIO(archive)) as bundle:
            for member in bundle.infolist():
                if any(ord(char) < 32 for char in member.filename):
                    raise SkillError("Skill 压缩包包含控制字符路径")
                member_path = PurePosixPath(member.filename)
                if member_path.is_absolute() or ".." in member_path.parts:
                    raise SkillError("Skill 压缩包包含目录穿越路径")
                if "\\" in member.filename or not member_path.parts:
                    raise SkillError("Skill 压缩包包含非标准路径")
                mode = (member.external_attr >> 16) & 0xFFFF
                if stat.S_ISLNK(mode):
                    raise SkillError("Skill 压缩包不允许符号链接")
                relative_repo_path = PurePosixPath(*member_path.parts[1:])
                try:
                    relative = relative_repo_path.relative_to(selected)
                except ValueError:
                    continue
                if not relative.parts:
                    found = True
                    continue
                found = True
                if member.is_dir():
                    continue
                file_count += 1
                total_size += member.file_size
                if file_count > self._settings.skill_max_files:
                    raise SkillError("Skill 文件数量超过限制")
                if member.file_size > self._settings.skill_max_file_bytes:
                    raise SkillError("Skill 单文件大小超过限制")
                if total_size > self._settings.skill_max_extracted_bytes:
                    raise SkillError("Skill 解压后总大小超过限制")
                target = output.joinpath(*relative.parts)
                resolved = target.resolve()
                if output not in resolved.parents:
                    raise SkillError("Skill 解压目标越过隔离目录")
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(member) as source, target.open("wb") as destination:
                    shutil.copyfileobj(source, destination)
        if not found:
            raise SkillError("GitHub 仓库中不存在指定 Skill 路径")
        return output

    def _validate_skill(self, path: Path, source_path: str) -> tuple[str, str]:
        manifest = path / "SKILL.md"
        if not manifest.is_file():
            raise SkillError("Skill 缺少 SKILL.md")
        if manifest.stat().st_size > 64 * 1024:
            raise SkillError("SKILL.md 超过 64 KiB 限制")
        try:
            text = manifest.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            raise SkillError("SKILL.md 必须使用 UTF-8 编码") from exc
        if not text.startswith("---\n"):
            raise SkillError("SKILL.md 缺少 YAML Frontmatter")
        parts = text.split("---", 2)
        if len(parts) != 3:
            raise SkillError("SKILL.md Frontmatter 未闭合")
        try:
            metadata = yaml.safe_load(parts[1])
        except yaml.YAMLError as exc:
            raise SkillError("SKILL.md Frontmatter 不是有效 YAML") from exc
        if not isinstance(metadata, dict):
            raise SkillError("SKILL.md Frontmatter 必须是对象")
        name = metadata.get("name")
        description = metadata.get("description")
        if not isinstance(name, str) or not SKILL_NAME_PATTERN.fullmatch(name):
            raise SkillError("Skill name 必须使用小写字母、数字和连字符")
        expected_name = PurePosixPath(source_path).name
        if name != expected_name:
            raise SkillError("Skill name 必须与来源目录名称一致")
        if not isinstance(description, str) or not description.strip():
            raise SkillError("Skill description 不能为空")
        if any(ord(char) < 32 and char not in "\n\r\t" for char in description):
            raise SkillError("Skill description 包含非法控制字符")
        if len(description) > 2000:
            raise SkillError("Skill description 超过长度限制")
        if not parts[2].strip():
            raise SkillError("SKILL.md 缺少使用说明")
        return name, description.strip()

    @staticmethod
    def _content_checksum(path: Path) -> str:
        digest = hashlib.sha256()
        for file in sorted(item for item in path.rglob("*") if item.is_file()):
            relative = file.relative_to(path).as_posix().encode("utf-8")
            digest.update(len(relative).to_bytes(4, "big"))
            digest.update(relative)
            content = file.read_bytes()
            digest.update(len(content).to_bytes(8, "big"))
            digest.update(content)
        return digest.hexdigest()

    @property
    def _registry_path(self) -> Path:
        return self._root / "registry.json"

    def _read_registry(self) -> dict[str, Any]:
        if not self._registry_path.exists():
            return {"schemaVersion": REGISTRY_SCHEMA_VERSION, "skills": {}}
        try:
            data = json.loads(self._registry_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise SkillError("Skill 注册表损坏或不可读") from exc
        if (
            not isinstance(data, dict)
            or data.get("schemaVersion") != REGISTRY_SCHEMA_VERSION
            or not isinstance(data.get("skills"), dict)
        ):
            raise SkillError("Skill 注册表格式不受支持")
        return data

    def _write_registry(self, registry: dict[str, Any]) -> None:
        temporary = self._root / f".registry-{uuid.uuid4().hex}.tmp"
        try:
            temporary.write_text(
                json.dumps(registry, ensure_ascii=False, indent=2, sort_keys=True),
                encoding="utf-8",
            )
            os.replace(temporary, self._registry_path)
        finally:
            temporary.unlink(missing_ok=True)
