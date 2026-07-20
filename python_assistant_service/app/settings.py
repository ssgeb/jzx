"""服务配置，全部敏感项均从环境变量读取。"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _as_bool(value: str | None, default: bool) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


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
        )
