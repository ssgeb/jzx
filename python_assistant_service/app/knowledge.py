"""可离线降级的本地 Markdown RAG 检索。"""

from __future__ import annotations

import re
import time
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path

from .settings import Settings


LATIN_TERM = re.compile(r"[a-zA-Z0-9_\-/]{2,}")
CHINESE_TERM = re.compile(r"[\u4e00-\u9fff]{2,}")


@dataclass(frozen=True)
class KnowledgeChunk:
    source: str
    title: str
    content: str
    normalized_content: str


class LocalKnowledgeBase:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._chunks: list[KnowledgeChunk] = []
        self._cache: OrderedDict[str, tuple[float, str]] = OrderedDict()
        self.reload()

    @property
    def chunk_count(self) -> int:
        return len(self._chunks)

    def reload(self) -> None:
        self._chunks.clear()
        self._cache.clear()
        if not self._settings.rag_enabled:
            return
        for raw_source in self._settings.rag_sources:
            path = Path(raw_source)
            if not path.is_file():
                continue
            try:
                self._split_source(str(path), path.read_text(encoding="utf-8"))
            except (OSError, UnicodeError):
                continue

    async def retrieve(self, query: str) -> str:
        if not self._settings.rag_enabled or not query.strip() or not self._chunks:
            return ""
        cache_key = " ".join(query.lower().split())
        cached = self._cache.get(cache_key)
        now = time.monotonic()
        if cached and cached[0] > now:
            self._cache.move_to_end(cache_key)
            return cached[1]

        terms = self._extract_terms(query)
        if not terms:
            return ""
        scored = sorted(
            ((self._score(chunk, terms), chunk) for chunk in self._chunks),
            key=lambda item: item[0],
            reverse=True,
        )
        matched = [chunk for score, chunk in scored if score > 0][
            : max(1, self._settings.rag_top_k)
        ]
        if not matched:
            return ""

        parts = ["[系统知识库检索结果: Python Local RAG]"]
        for chunk in matched:
            candidate = f"- 来源：{chunk.title}\n{chunk.content}"
            if len("\n\n".join(parts + [candidate])) > self._settings.rag_max_context_chars:
                break
            parts.append(candidate)
        context = "\n\n".join(parts)
        if len(context) > self._settings.rag_max_context_chars:
            context = context[: self._settings.rag_max_context_chars] + "\n[知识库上下文已截断]"
        self._put_cache(cache_key, context, now)
        return context

    def _split_source(self, source: str, content: str) -> None:
        title = source
        buffer: list[str] = []
        size = max(300, self._settings.rag_chunk_size)
        for raw_line in content.splitlines():
            line = raw_line.strip()
            if line.startswith("#"):
                self._flush(source, title, buffer)
                title = re.sub(r"^#+\s*", "", line)
                buffer = []
            if line:
                buffer.append(line)
            if sum(len(item) + 1 for item in buffer) >= size:
                self._flush(source, title, buffer)
                buffer = []
        self._flush(source, title, buffer)

    def _flush(self, source: str, title: str, buffer: list[str]) -> None:
        text = "\n".join(buffer).strip()
        if len(text) >= 20:
            self._chunks.append(KnowledgeChunk(source, title, text, text.lower()))

    @staticmethod
    def _extract_terms(query: str) -> list[str]:
        terms: dict[str, None] = {}
        for match in LATIN_TERM.finditer(query.lower()):
            terms[match.group()] = None
        for match in CHINESE_TERM.finditer(query):
            token = match.group()
            terms[token] = None
            for size in (2, 4):
                for index in range(max(0, len(token) - size + 1)):
                    terms[token[index:index + size]] = None
        return list(terms)[:80]

    @staticmethod
    def _score(chunk: KnowledgeChunk, terms: list[str]) -> int:
        title = chunk.title.lower()
        score = 0
        for term in terms:
            normalized = term.lower()
            if normalized in chunk.normalized_content:
                score += 2
            if normalized in title:
                score += 4
        return score

    def _put_cache(self, key: str, value: str, now: float) -> None:
        ttl = self._settings.rag_cache_ttl_seconds
        if ttl <= 0 or not value:
            return
        self._cache[key] = (now + ttl, value)
        self._cache.move_to_end(key)
        max_entries = max(8, self._settings.rag_cache_max_entries)
        while len(self._cache) > max_entries:
            self._cache.popitem(last=False)
