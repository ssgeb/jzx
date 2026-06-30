package com.ruanzhu.doorhandlecatch.stategraph.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.mapper.ChatSessionMapper;
import com.ruanzhu.doorhandlecatch.stategraph.core.AgentState;
import com.ruanzhu.doorhandlecatch.stategraph.core.StateGraphException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 基于 MySQL chat_session.state_json 列的 Checkpointer 实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySqlCheckpointer implements Checkpointer {

    private final ChatSessionMapper chatSessionMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void save(String threadId, AgentState state) {
        try {
            String json = objectMapper.writeValueAsString(state.toMap());
            int updated = chatSessionMapper.updateCheckpoint(
                    threadId,
                    json,
                    state.getString(AgentState.KEY_CURRENT_NODE),
                    state.getString(AgentState.KEY_EXIT_REASON)
            );
            if (updated == 0) {
                log.warn("Checkpoint save 未更新任何行: thread_id={}", threadId);
            }
        } catch (JsonProcessingException e) {
            throw new StateGraphException("Checkpoint 序列化失败", e, state);
        }
    }

    @Override
    public AgentState load(String threadId) {
        String json = chatSessionMapper.selectStateJson(threadId);
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return AgentState.fromMap(map);
        } catch (JsonProcessingException e) {
            throw new StateGraphException("Checkpoint 反序列化失败: thread_id=" + threadId, e);
        }
    }

    @Override
    public void delete(String threadId) {
        chatSessionMapper.clearCheckpoint(threadId);
    }
}
