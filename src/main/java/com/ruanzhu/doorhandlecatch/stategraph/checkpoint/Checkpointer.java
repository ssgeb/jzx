package com.ruanzhu.doorhandlecatch.stategraph.checkpoint;

import com.ruanzhu.doorhandlecatch.stategraph.core.AgentState;

/**
 * StateGraph 持久化接口。
 * thread_id 等价于 chat_session.session_id，实现用户/会话隔离。
 */
public interface Checkpointer {

    void save(String threadId, AgentState state);

    AgentState load(String threadId);

    void delete(String threadId);
}
