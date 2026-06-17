package com.ruanzhu.doorhandlecatch.service.impl;

import com.ruanzhu.doorhandlecatch.entity.ChatProject;
import com.ruanzhu.doorhandlecatch.entity.ChatSession;
import com.ruanzhu.doorhandlecatch.mapper.ChatProjectMapper;
import com.ruanzhu.doorhandlecatch.mapper.ChatSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatProjectServiceImplTest {

    private ChatProjectMapper chatProjectMapper;
    private ChatSessionMapper chatSessionMapper;
    private ChatProjectServiceImpl chatProjectService;

    @BeforeEach
    void setUp() {
        chatProjectMapper = mock(ChatProjectMapper.class);
        chatSessionMapper = mock(ChatSessionMapper.class);
        chatProjectService = new ChatProjectServiceImpl(chatProjectMapper, chatSessionMapper);
    }

    @Test
    void shouldDetachOnlyCurrentUsersSessionsWhenDeletingProject() {
        ChatProject project = new ChatProject();
        project.setId(10L);
        project.setProjectId("proj_admin_001");
        project.setUsername("admin");

        ChatSession ownSession = new ChatSession();
        ownSession.setSessionId("sess_admin_001");
        ownSession.setUsername("admin");
        ownSession.setProjectId("proj_admin_001");

        ChatSession otherSession = new ChatSession();
        otherSession.setSessionId("sess_other_001");
        otherSession.setUsername("other");
        otherSession.setProjectId("proj_admin_001");

        when(chatProjectMapper.selectOne(any())).thenReturn(project);
        when(chatSessionMapper.selectList(any())).thenReturn(List.of(ownSession, otherSession));

        chatProjectService.deleteProject("admin", "proj_admin_001");

        assertThat(ownSession.getProjectId()).isNull();
        assertThat(otherSession.getProjectId()).isEqualTo("proj_admin_001");
        verify(chatSessionMapper).updateById(ownSession);
        verify(chatProjectMapper).deleteById(10L);
    }
}
