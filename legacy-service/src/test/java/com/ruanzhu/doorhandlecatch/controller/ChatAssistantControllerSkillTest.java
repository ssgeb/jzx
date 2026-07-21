package com.ruanzhu.doorhandlecatch.controller;

import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.dto.chat.AgentSkillInstallRequest;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonSkillInstallRequest;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonSkillListResponse;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonSkillRecord;
import com.ruanzhu.doorhandlecatch.service.AgentGraphRunMonitor;
import com.ruanzhu.doorhandlecatch.service.AgentOrchestratorService;
import com.ruanzhu.doorhandlecatch.service.ChatSessionService;
import com.ruanzhu.doorhandlecatch.service.PythonAssistantClient;
import com.ruanzhu.doorhandlecatch.service.SpeechTranscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatAssistantControllerSkillTest {

    private PythonAssistantClient pythonClient;
    private ChatAssistantController controller;

    @BeforeEach
    void setUp() {
        pythonClient = mock(PythonAssistantClient.class);
        controller = new ChatAssistantController(
                mock(ChatSessionService.class),
                mock(AgentOrchestratorService.class),
                mock(AgentGraphRunMonitor.class),
                mock(SpeechTranscriptionService.class),
                pythonClient);
    }

    @Test
    void adminCanListAndDownloadSkillWhileRequestedByComesFromAuthentication() {
        PythonSkillListResponse list = new PythonSkillListResponse();
        list.setEnabled(true);
        PythonSkillRecord record = new PythonSkillRecord();
        record.setName("demo-skill");
        record.setStatus("QUARANTINED");
        when(pythonClient.listSkills()).thenReturn(list);
        when(pythonClient.installSkill(org.mockito.ArgumentMatchers.any())).thenReturn(record);
        AgentSkillInstallRequest request = new AgentSkillInstallRequest();
        request.setRepository("OpenAI/Skills");
        request.setPath("skills/.curated/demo-skill");
        request.setRef("main");

        assertThat(controller.listSkills(authentication("admin", "ROLE_ADMIN")).getData())
                .isSameAs(list);
        assertThat(controller.installSkill(
                authentication("admin", "ROLE_ADMIN"), request).getData()).isSameAs(record);

        ArgumentCaptor<PythonSkillInstallRequest> captor =
                ArgumentCaptor.forClass(PythonSkillInstallRequest.class);
        verify(pythonClient).installSkill(captor.capture());
        assertThat(captor.getValue().getRepository()).isEqualTo("openai/skills");
        assertThat(captor.getValue().getRequestedBy()).isEqualTo("admin");
    }

    @Test
    void normalEnterpriseUserCannotListOrDownloadSkills() {
        Authentication user = authentication("alice", "ROLE_OPERATOR");
        AgentSkillInstallRequest request = new AgentSkillInstallRequest();
        request.setRepository("openai/skills");
        request.setPath("skills/.curated/demo-skill");
        request.setRef("main");

        assertThatThrownBy(() -> controller.listSkills(user))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(403);
        assertThatThrownBy(() -> controller.installSkill(user, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(403);
        verifyNoInteractions(pythonClient);
    }

    private Authentication authentication(String username, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                username,
                "ignored",
                List.of(new SimpleGrantedAuthority(role)));
    }
}
