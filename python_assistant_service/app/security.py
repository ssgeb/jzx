"""内部请求 HMAC 签名与重放防护。"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import time
from typing import Protocol

from fastapi import HTTPException, Request, status

from .settings import Settings


def canonical_signature_payload(
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    body: bytes,
) -> bytes:
    body_hash = hashlib.sha256(body).hexdigest()
    return "\n".join((method.upper(), path, timestamp, nonce, body_hash)).encode("utf-8")


def create_signature(
    secret: str,
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    body: bytes,
) -> str:
    payload = canonical_signature_payload(method, path, timestamp, nonce, body)
    return hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest()


class ReplayGuard(Protocol):
    async def claim(self, nonce: str, ttl_seconds: int) -> bool: ...


class InMemoryReplayGuard:
    """单实例开发与测试使用；生产多副本应配置 Redis。"""

    def __init__(self) -> None:
        self._expires_at: dict[str, float] = {}
        self._lock = asyncio.Lock()

    async def claim(self, nonce: str, ttl_seconds: int) -> bool:
        now = time.time()
        async with self._lock:
            self._expires_at = {
                key: expiry for key, expiry in self._expires_at.items() if expiry > now
            }
            if nonce in self._expires_at:
                return False
            self._expires_at[nonce] = now + ttl_seconds
            return True


class RedisReplayGuard:
    def __init__(self, redis_url: str) -> None:
        try:
            from redis.asyncio import Redis
        except ImportError as exc:  # pragma: no cover - 仅配置错误时触发
            raise RuntimeError("已配置 Redis 重放防护，但未安装 redis 依赖") from exc
        self._redis = Redis.from_url(redis_url, decode_responses=True)

    async def claim(self, nonce: str, ttl_seconds: int) -> bool:
        result = await self._redis.set(
            f"assistant:nonce:{nonce}", "1", ex=ttl_seconds, nx=True
        )
        return bool(result)


def build_replay_guard(settings: Settings) -> ReplayGuard:
    if settings.replay_redis_url:
        return RedisReplayGuard(settings.replay_redis_url)
    return InMemoryReplayGuard()


async def verify_internal_request(
    request: Request,
    settings: Settings,
    replay_guard: ReplayGuard,
) -> None:
    if not settings.require_signature:
        return
    if not settings.hmac_secret:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="智能体服务缺少 HMAC 密钥",
        )

    timestamp = request.headers.get("X-Timestamp", "")
    nonce = request.headers.get("X-Nonce", "")
    supplied = request.headers.get("X-Signature", "")
    if not timestamp or not nonce or not supplied:
        raise HTTPException(status_code=401, detail="内部请求签名不完整")
    try:
        timestamp_value = int(timestamp)
    except ValueError as exc:
        raise HTTPException(status_code=401, detail="内部请求时间戳无效") from exc

    now = int(time.time())
    if abs(now - timestamp_value) > settings.signature_max_skew_seconds:
        raise HTTPException(status_code=401, detail="内部请求已过期")

    body = await request.body()
    expected = create_signature(
        settings.hmac_secret,
        request.method,
        request.url.path,
        timestamp,
        nonce,
        body,
    )
    if not hmac.compare_digest(expected, supplied):
        raise HTTPException(status_code=401, detail="内部请求签名无效")

    claimed = await replay_guard.claim(
        nonce, max(settings.signature_max_skew_seconds * 2, 60)
    )
    if not claimed:
        raise HTTPException(status_code=409, detail="检测到重复内部请求")
