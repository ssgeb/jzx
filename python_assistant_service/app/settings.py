"""服务配置，全部敏感项均从环境变量读取。"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _as_bool(value: str | None, default: bool) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _as_csv(value: str | None, default: tuple[str, ...]) -> tuple[str, ...]:
    if value is None:
        return default
    return tuple(item.strip() for item in value.split(",") if item.strip())


@dataclass(frozen=True)
class Settings:
    service_name: str = "doorhandlecatch-python-assistant"
    graph_version: str = "python-langgraph-v1"
    host: str = "127.0.0.1"
    port: int = 8090
    require_signature: bool = True
    hmac_secret: str | None = None
    replay_redis_url: str | None = None
    signature_max_skew_seconds: int = 60
    java_tool_base_url: str | None = None
    java_tool_timeout_seconds: float = 15.0
    deepseek_enabled: bool = False
    deepseek_api_key: str | None = None
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"
    max_graph_iterations: int = 15
    max_trace_size: int = 24
    rag_enabled: bool = True
    rag_sources: tuple[str, ...] = (
        "legacy-service/src/main/resources/rag/system-user-guide.md",
        "legacy-service/src/main/resources/rag/assistant-rag-guide.md",
        "README.md",
    )
    rag_chunk_size: int = 900
    rag_top_k: int = 4
    rag_max_context_chars: int = 2600
    rag_cache_ttl_seconds: int = 120
    rag_cache_max_entries: int = 128
    memory_enabled: bool = True
    memory_service_url: str = "http://127.0.0.1:8081"
    memory_connect_timeout_seconds: float = 2.0
    memory_read_timeout_seconds: float = 5.0
    memory_top_k: int = 5

    @classmethod
    def from_env(cls) -> "Settings":
        api_key = os.getenv("DEEPSEEK_API_KEY")
        return cls(
            host=os.getenv("ASSISTANT_HOST", "127.0.0.1"),
            port=int(os.getenv("ASSISTANT_PORT", "8090")),
            require_signature=_as_bool(os.getenv("ASSISTANT_REQUIRE_SIGNATURE"), True),
            hmac_secret=os.getenv("ASSISTANT_HMAC_SECRET"),
            replay_redis_url=os.getenv("ASSISTANT_REPLAY_REDIS_URL"),
            signature_max_skew_seconds=int(os.getenv("ASSISTANT_SIGNATURE_MAX_SKEW_SECONDS", "60")),
            java_tool_base_url=os.getenv("JAVA_AGENT_TOOL_BASE_URL"),
            java_tool_timeout_seconds=float(os.getenv("JAVA_AGENT_TOOL_TIMEOUT_SECONDS", "15")),
            deepseek_enabled=_as_bool(os.getenv("DEEPSEEK_ENABLED"), bool(api_key)),
            deepseek_api_key=api_key,
            deepseek_base_url=os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
            deepseek_model=os.getenv("DEEPSEEK_MODEL", "deepseek-chat"),
            max_graph_iterations=int(os.getenv("ASSISTANT_MAX_GRAPH_ITERATIONS", "15")),
            max_trace_size=int(os.getenv("ASSISTANT_MAX_TRACE_SIZE", "24")),
            rag_enabled=_as_bool(os.getenv("ASSISTANT_RAG_ENABLED"), True),
            rag_sources=_as_csv(os.getenv("ASSISTANT_RAG_SOURCES"), cls.rag_sources),
            rag_chunk_size=int(os.getenv("ASSISTANT_RAG_CHUNK_SIZE", "900")),
            rag_top_k=int(os.getenv("ASSISTANT_RAG_TOP_K", "4")),
            rag_max_context_chars=int(os.getenv("ASSISTANT_RAG_MAX_CONTEXT_CHARS", "2600")),
            rag_cache_ttl_seconds=int(os.getenv("ASSISTANT_RAG_CACHE_TTL_SECONDS", "120")),
            rag_cache_max_entries=int(os.getenv("ASSISTANT_RAG_CACHE_MAX_ENTRIES", "128")),
            memory_enabled=_as_bool(
                os.getenv("ASSISTANT_MEMORY_ENABLED", os.getenv("MEM0_ENABLED")), True
            ),
            memory_service_url=os.getenv("MEM0_SERVICE_URL", "http://127.0.0.1:8081"),
            memory_connect_timeout_seconds=float(os.getenv("MEM0_CONNECT_TIMEOUT_MS", "2000")) / 1000,
            memory_read_timeout_seconds=float(os.getenv("MEM0_READ_TIMEOUT_MS", "5000")) / 1000,
            memory_top_k=int(os.getenv("ASSISTANT_MEMORY_TOP_K", "5")),
        )
