package com.ruanzhu.doorhandlecatch.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.config.properties.ChatAssistantProperties;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatSessionResponse;
import com.ruanzhu.doorhandlecatch.entity.ChatMessage;
import com.ruanzhu.doorhandlecatch.entity.ChatPendingAction;
import com.ruanzhu.doorhandlecatch.entity.ChatSession;
import com.ruanzhu.doorhandlecatch.mapper.ChatMessageMapper;
import com.ruanzhu.doorhandlecatch.mapper.ChatPendingActionMapper;
import com.ruanzhu.doorhandlecatch.mapper.ChatSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionServiceImplTest {

    private ChatSessionMapper chatSessionMapper;
    private ChatMessageMapper chatMessageMapper;
    private ChatPendingActionMapper pendingActionMapper;
    private ChatSessionServiceImpl chatSessionService;

    @BeforeEach
    void setUp() {
        chatSessionMapper = mock(ChatSessionMapper.class);
        chatMessageMapper = mock(ChatMessageMapper.class);
        pendingActionMapper = mock(ChatPendingActionMapper.class);

        ChatAssistantProperties properties = new ChatAssistantProperties();
        properties.setDefaultSessionSuffix("default");
        properties.setMaxHistoryMessages(30);

        chatSessionService = new ChatSessionServiceImpl(
                chatSessionMapper,
                chatMessageMapper,
                pendingActionMapper,
                properties,
                new ObjectMapper()
        );
    }

    @Test
    void shouldCreateDefaultSessionWhenMissing() {
        when(chatSessionMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(chatSessionMapper.selectOne(any())).thenReturn(null);
        when(chatMessageMapper.findBySessionId(anyString())).thenReturn(Collections.emptyList());

        ChatSessionResponse response = chatSessionService.getOrCreateActiveSession("admin");

        assertThat(response.getSessionId()).startsWith("sess_admin_");
        verify(chatSessionMapper).insert(any(ChatSession.class));
        verify(chatMessageMapper).insert(any(ChatMessage.class));
    }

    @Test
    void shouldAppendAssistantMessage() {
        ChatMessage message = new ChatMessage();
        message.setId(1L);

        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        chatSessionService.appendAssistantMessage("sess_admin_default", "测试消息", "TEXT", "TEST", null);

        verify(chatMessageMapper).insert(captor.capture());
        assertThat(captor.getValue().getSessionId()).isEqualTo("sess_admin_default");
        assertThat(captor.getValue().getRole()).isEqualTo("assistant");
    }

    @Test
    void shouldRejectSessionAccessWhenOwnerDoesNotMatch() {
        when(chatSessionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> chatSessionService.getSession("other", "sess_admin_default"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("会话不存在");
    }

    @Test
    void shouldLoadMessagesOnlyAfterOwnerVerified() {
        ChatSession session = new ChatSession();
        session.setSessionId("sess_admin_default");
        session.setUsername("admin");
        session.setStatus("ACTIVE");
        when(chatSessionMapper.selectOne(any())).thenReturn(session);
        when(chatMessageMapper.findBySessionId("sess_admin_default")).thenReturn(Collections.emptyList());

        assertThat(chatSessionService.listMessages("admin", "sess_admin_default")).isEmpty();

        verify(chatMessageMapper).findBySessionId("sess_admin_default");
    }

    @Test
    void shouldMarkPendingActionBySessionAndActionId() {
        ChatPendingAction action = new ChatPendingAction();
        action.setSessionId("sess_admin_default");
        action.setActionId("action-1");
        when(pendingActionMapper.selectOne(any())).thenReturn(action);

        chatSessionService.markPendingActionStatus("sess_admin_default", "action-1", "CONFIRMED");

        assertThat(action.getStatus()).isEqualTo("CONFIRMED");
        verify(pendingActionMapper).updateById(action);
    }
}
