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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import com.ruanzhu.doorhandlecatch.security.TenantPrincipal;
import com.ruanzhu.doorhandlecatch.security.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionServiceImplTest {

    @BeforeAll
    static void initMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ChatSession.class);
    }

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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createdSessionStoresImmutableTenantUserId() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new TenantPrincipal(42L, "alice", "N/A", List.of()), null, List.of()));
        when(chatMessageMapper.findBySessionId(anyString())).thenReturn(Collections.emptyList());
        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);

        chatSessionService.createSession("alice");

        verify(chatSessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(42L);
    }

    @Test
    void sessionListFiltersByImmutableTenantUserId() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new TenantPrincipal(42L, "alice", "N/A", List.of()), null, List.of()));
        ArgumentCaptor<LambdaQueryWrapper<ChatSession>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        when(chatSessionMapper.selectList(captor.capture())).thenReturn(Collections.emptyList());

        chatSessionService.listUserSessions("alice");

        assertThat(captor.getValue().getSqlSegment()).contains("user_id").doesNotContain("username");
    }

    @Test
    void activeSessionLookupFiltersByImmutableTenantUserId() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new TenantPrincipal(42L, "alice", "N/A", List.of()), null, List.of()));
        ArgumentCaptor<LambdaQueryWrapper<ChatSession>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        ChatSession active = new ChatSession();
        active.setSessionId("sess_alice_001");
        active.setUserId(42L);
        active.setUsername("alice");
        when(chatSessionMapper.selectList(captor.capture())).thenReturn(List.of(active));
        when(chatMessageMapper.findBySessionId(active.getSessionId())).thenReturn(List.of());

        chatSessionService.getOrCreateActiveSession("alice");

        assertThat(captor.getValue().getSqlSegment()).contains("user_id").doesNotContain("username");
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
    void shouldRejectMessageWriteWhenSessionDoesNotBelongToTenant() {
        TenantContext tenant = new TenantContext(42L, "alice");
        when(chatSessionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> chatSessionService.appendUserMessage(tenant, "sess_other", "hello"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("会话不存在");
        verify(chatMessageMapper, never()).insert(any(ChatMessage.class));
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

    @Test
    void transitionsOnlyFromExpectedPendingStatus() {
        when(pendingActionMapper.transitionStatus(
                "sess_admin_default", "action-1", "PENDING", "EXECUTING", null))
                .thenReturn(1);

        boolean claimed = chatSessionService.transitionPendingAction(
                "sess_admin_default", "action-1", "PENDING", "EXECUTING", null);

        assertThat(claimed).isTrue();
    }
}
