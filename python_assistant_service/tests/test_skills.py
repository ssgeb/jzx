from __future__ import annotations

import asyncio
import json
import shutil
import stat
import time
import uuid
import zipfile
from io import BytesIO

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from python_assistant_service.app.main import create_app
from python_assistant_service.app.schemas import (
    SkillInstallRequest,
    SkillListResponse,
    SkillRecord,
)
from python_assistant_service.app.security import InMemoryReplayGuard, create_signature
from python_assistant_service.app.settings import Settings
from python_assistant_service.app.skills import SkillCatalog, SkillError


REPOSITORY = "openai/skills"
SOURCE_PATH = "skills/.curated/demo-skill"


def skill_archive(*, name: str = "demo-skill", extra_members=None) -> bytes:
    output = BytesIO()
    prefix = "openai-skills-abcdef/skills/.curated/demo-skill"
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as bundle:
        bundle.writestr(
            f"{prefix}/SKILL.md",
            f"""---
name: {name}
description: 用于测试安全下载流程。
---

# 使用说明

只读取经过授权的数据。
""",
        )
        bundle.writestr(f"{prefix}/references/policy.md", "不得执行未授权操作。")
        for item, content in extra_members or []:
            if isinstance(item, zipfile.ZipInfo):
                bundle.writestr(item, content)
            else:
                bundle.writestr(item, content)
    return output.getvalue()


def install_request() -> SkillInstallRequest:
    return SkillInstallRequest(
        repository=REPOSITORY,
        path=SOURCE_PATH,
        ref="main",
        requested_by="admin",
    )


def catalog(tmp_path, handler, **overrides):
    settings = Settings(
        skills_root=str(tmp_path / "skills"),
        skill_allowed_repositories=(REPOSITORY,),
        **overrides,
    )
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return SkillCatalog(settings, client), client


def test_download_validates_and_installs_skill_in_quarantine(tmp_path):
    archive = skill_archive()

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.host == "api.github.com":
            return httpx.Response(
                302,
                headers={
                    "Location": "https://codeload.github.com/openai/skills/legacy.zip/main"
                },
            )
        return httpx.Response(200, content=archive)

    store, client = catalog(tmp_path, handler)
    record = asyncio.run(store.install(install_request()))
    listed = asyncio.run(store.list())
    asyncio.run(client.aclose())

    assert record.name == "demo-skill"
    assert record.status == "QUARANTINED"
    assert record.installed_by == "admin"
    assert len(record.checksum) == 64
    assert (tmp_path / "skills/demo-skill/SKILL.md").is_file()
    assert listed.skills == [record]


def test_repository_must_be_explicitly_allowed_before_network_call(tmp_path):
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url)
        return httpx.Response(500)

    store, client = catalog(tmp_path, handler)
    request = install_request().model_copy(update={"repository": "other/repository"})

    with pytest.raises(SkillError, match="不在允许列表"):
        asyncio.run(store.install(request))
    asyncio.run(client.aclose())

    assert calls == []


@pytest.mark.parametrize("repository", ["../repository", "owner/..", ".hidden/repo"])
def test_repository_components_cannot_be_dot_paths(repository):
    with pytest.raises(ValidationError, match="owner/repository"):
        SkillInstallRequest(
            repository=repository,
            path=SOURCE_PATH,
            ref="main",
            requested_by="admin",
        )


def test_redirect_to_non_github_host_is_rejected_before_following(tmp_path):
    requested_hosts = []

    def handler(request: httpx.Request) -> httpx.Response:
        requested_hosts.append(request.url.host)
        return httpx.Response(302, headers={"Location": "https://evil.example/skill.zip"})

    store, client = catalog(tmp_path, handler)

    with pytest.raises(SkillError, match="主机允许列表"):
        asyncio.run(store.install(install_request()))
    asyncio.run(client.aclose())

    assert requested_hosts == ["api.github.com"]


def test_declared_download_size_over_limit_is_rejected(tmp_path):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, headers={"Content-Length": "1024"}, content=b"x")

    store, client = catalog(tmp_path, handler, skill_max_download_bytes=128)

    with pytest.raises(SkillError, match="下载大小限制"):
        asyncio.run(store.install(install_request()))
    asyncio.run(client.aclose())


def test_extracted_file_size_over_limit_is_rejected(tmp_path):
    archive = skill_archive(
        extra_members=[
            (
                "openai-skills-abcdef/skills/.curated/demo-skill/references/large.txt",
                "x" * 1024,
            )
        ]
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=archive)

    store, client = catalog(tmp_path, handler, skill_max_file_bytes=512)

    with pytest.raises(SkillError, match="单文件大小"):
        asyncio.run(store.install(install_request()))
    asyncio.run(client.aclose())


def test_zip_traversal_is_rejected_without_writing_outside_root(tmp_path):
    archive = skill_archive(
        extra_members=[("openai-skills-abcdef/../escape.txt", "escape")]
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=archive)

    store, client = catalog(tmp_path, handler)

    with pytest.raises(SkillError, match="目录穿越"):
        asyncio.run(store.install(install_request()))
    asyncio.run(client.aclose())

    assert not (tmp_path / "escape.txt").exists()
    assert not (tmp_path / "skills/demo-skill").exists()


def test_symbolic_links_and_duplicate_install_are_rejected(tmp_path):
    link = zipfile.ZipInfo(
        "openai-skills-abcdef/skills/.curated/demo-skill/references/link"
    )
    link.create_system = 3
    link.external_attr = (stat.S_IFLNK | 0o777) << 16
    unsafe_archive = skill_archive(extra_members=[(link, "../../outside")])
    valid_archive = skill_archive()
    current_archive = unsafe_archive

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=current_archive)

    store, client = catalog(tmp_path, handler)
    with pytest.raises(SkillError, match="符号链接"):
        asyncio.run(store.install(install_request()))

    current_archive = valid_archive
    asyncio.run(store.install(install_request()))
    with pytest.raises(SkillError, match="已存在"):
        asyncio.run(store.install(install_request()))
    asyncio.run(client.aclose())


def test_registry_prevents_reinstall_when_skill_directory_was_removed(tmp_path):
    archive = skill_archive()

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=archive)

    store, client = catalog(tmp_path, handler)
    asyncio.run(store.install(install_request()))
    shutil.rmtree(tmp_path / "skills/demo-skill")

    with pytest.raises(SkillError, match="已存在"):
        asyncio.run(store.install(install_request()))
    asyncio.run(client.aclose())


def test_manifest_name_must_match_source_directory(tmp_path):
    archive = skill_archive(name="different-skill")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=archive)

    store, client = catalog(tmp_path, handler)

    with pytest.raises(SkillError, match="来源目录名称一致"):
        asyncio.run(store.install(install_request()))
    asyncio.run(client.aclose())


class StubAgentService:
    async def shutdown(self):
        return None


class StubSkillCatalog:
    def __init__(self):
        self.request = None
        self.record = SkillRecord(
            name="demo-skill",
            description="测试 Skill",
            repository=REPOSITORY,
            source_path=SOURCE_PATH,
            ref="main",
            checksum="a" * 64,
            status="QUARANTINED",
            installed_at="2026-07-21T00:00:00+00:00",
            installed_by="admin",
        )

    async def list(self):
        return SkillListResponse(enabled=True, skills=[self.record])

    async def install(self, request):
        self.request = request
        return self.record


def signed_headers(secret: str, path: str, body: bytes):
    timestamp = str(int(time.time()))
    nonce = str(uuid.uuid4())
    return {
        "Content-Type": "application/json",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": create_signature(secret, "POST", path, timestamp, nonce, body),
    }


def test_internal_skill_endpoints_require_signature_and_preserve_request():
    secret = "skill-api-contract-secret-at-least-32-chars"
    skill_store = StubSkillCatalog()
    app = create_app(
        Settings(require_signature=True, hmac_secret=secret),
        StubAgentService(),
        InMemoryReplayGuard(),
        skill_store,
    )
    list_path = "/internal/v1/skills/list"
    install_path = "/internal/v1/skills/install"
    body = json.dumps(
        install_request().model_dump(),
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")

    with TestClient(app) as client:
        unsigned = client.post(list_path)
        listed = client.post(
            list_path, content=b"", headers=signed_headers(secret, list_path, b"")
        )
        installed = client.post(
            install_path,
            content=body,
            headers=signed_headers(secret, install_path, body),
        )

    assert unsigned.status_code == 401
    assert listed.status_code == 200
    assert listed.json()["skills"][0]["status"] == "QUARANTINED"
    assert installed.status_code == 200
    assert skill_store.request.requested_by == "admin"
