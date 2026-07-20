package com.ruanzhu.doorhandlecatch.service;

import com.ruanzhu.doorhandlecatch.dto.chat.AgentExecutionResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 单实例写工具幂等保护。主流程的待确认动作 CAS 仍是第一道保护；该组件用于处理
 * Python 到 Java 的响应丢失和并发重复调用。多副本部署时应替换为 Redis/数据库实现。
 */
@Component
public class AgentToolIdempotencyGuard {

    private static final Duration RETENTION = Duration.ofHours(24);
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public AgentExecutionResult executeOnce(String key, Supplier<AgentExecutionResult> operation) {
        cleanupExpired();
        Entry candidate = new Entry(new CompletableFuture<>(), Instant.now().plus(RETENTION));
        Entry existing = entries.putIfAbsent(key, candidate);
        Entry selected = existing == null ? candidate : existing;
        if (existing == null) {
            try {
                AgentExecutionResult result = operation.get();
                candidate.result().complete(result);
                return result;
            } catch (RuntimeException ex) {
                candidate.result().completeExceptionally(ex);
                entries.remove(key, candidate);
                throw ex;
            }
        }
        try {
            return selected.result().join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private record Entry(CompletableFuture<AgentExecutionResult> result, Instant expiresAt) {
    }
}
