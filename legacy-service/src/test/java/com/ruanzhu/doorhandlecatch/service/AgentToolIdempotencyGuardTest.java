package com.ruanzhu.doorhandlecatch.service;

import com.ruanzhu.doorhandlecatch.dto.chat.AgentExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentToolIdempotencyGuardTest {

    @Test
    void repeatedKeyReusesFirstWriteResult() {
        AgentToolIdempotencyGuard guard = new AgentToolIdempotencyGuard();
        AtomicInteger executions = new AtomicInteger();

        AgentExecutionResult first = guard.executeOnce("user:session:action", () -> result(executions.incrementAndGet()));
        AgentExecutionResult second = guard.executeOnce("user:session:action", () -> result(executions.incrementAndGet()));

        assertThat(executions).hasValue(1);
        assertThat(second.getContent()).isEqualTo(first.getContent()).isEqualTo("result-1");
    }

    private AgentExecutionResult result(int value) {
        return AgentExecutionResult.builder()
                .messageType("TEXT")
                .content("result-" + value)
                .intent("RESOURCE_ACTION")
                .build();
    }
}
