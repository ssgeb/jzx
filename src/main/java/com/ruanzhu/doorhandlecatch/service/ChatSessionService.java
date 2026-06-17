package com.ruanzhu.doorhandlecatch.service;

import com.ruanzhu.doorhandlecatch.dto.chat.ChatMessageResponse;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatPendingActionPayload;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatSessionResponse;
import com.ruanzhu.doorhandlecatch.dto.chat.CheckpointSnapshotResponse;
import com.ruanzhu.doorhandlecatch.entity.ChatPendingAction;

import java.util.List;

public interface ChatSessionService {

    ChatSessionResponse getOrCreateActiveSession(String username);

    /** 获取用户的所有会话列表（不含消息内容） */
    List<ChatSessionResponse> listUserSessions(String username);

    /** 创建新会话 */
    ChatSessionResponse createSession(String username);

    /** 获取指定会话（含消息） */
    ChatSessionResponse getSession(String username, String sessionId);

    /** 获取智能体 checkpoint 调试快照（不暴露完整 state_json） */
    CheckpointSnapshotResponse getCheckpointSnapshot(String username, String sessionId);

    /** 归档会话 */
    void archiveSession(String username, String sessionId);

    /** 删除会话 */
    void deleteSession(String username, String sessionId);

    /** 重命名会话 */
    void renameSession(String username, String sessionId, String title);

    /** 置顶/取消置顶会话 */
    void togglePinSession(String username, String sessionId, boolean pinned);

    ChatMessageResponse appendUserMessage(String sessionId, String content);

    ChatMessageResponse appendAssistantMessage(String sessionId, String content, String messageType, String intent, String actionId);

    List<ChatMessageResponse> listMessages(String username, String sessionId);

    ChatPendingAction savePendingAction(String sessionId, String actionId, String actionType, ChatPendingActionPayload payload);

    ChatPendingAction getPendingAction(String sessionId, String actionId);

    void markPendingActionStatus(String sessionId, String actionId, String status);

    void verifySessionOwner(String username, String sessionId);

    void saveState(String sessionId, String stateJson);

    String loadState(String sessionId);
}
