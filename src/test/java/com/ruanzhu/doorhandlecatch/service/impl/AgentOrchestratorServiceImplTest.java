package com.ruanzhu.doorhandlecatch.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.config.properties.ChatAssistantProperties;
import com.ruanzhu.doorhandlecatch.config.properties.DeepSeekProperties;
import com.ruanzhu.doorhandlecatch.dto.chat.AgentRouteDecision;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatSendMessageRequest;
import com.ruanzhu.doorhandlecatch.service.ChatSessionService;
import com.ruanzhu.doorhandlecatch.service.ContextBuilder;
import com.ruanzhu.doorhandlecatch.service.DeepSeekClient;
import com.ruanzhu.doorhandlecatch.service.Mem0Client;
import com.ruanzhu.doorhandlecatch.service.RagKnowledgeService;
import com.ruanzhu.doorhandlecatch.stategraph.checkpoint.MySqlCheckpointer;
import com.ruanzhu.doorhandlecatch.stategraph.core.AgentState;
import com.ruanzhu.doorhandlecatch.stategraph.core.CompiledGraph;
import com.ruanzhu.doorhandlecatch.stategraph.node.RouterNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentOrchestratorServiceImplTest {

    private AgentOrchestratorServiceImpl orchestratorService;
    private ChatSessionService chatSessionService;

    @BeforeEach
    void setUp() {
        DeepSeekClient deepSeekClient = Mockito.mock(DeepSeekClient.class);
        DeepSeekProperties deepSeekProperties = new DeepSeekProperties();
        ContextBuilder contextBuilder = new ContextBuilder();
        RouterNode routerNode = new RouterNode(deepSeekClient, deepSeekProperties, contextBuilder);
        CompiledGraph chatGraph = Mockito.mock(CompiledGraph.class);
        chatSessionService = Mockito.mock(ChatSessionService.class);

        orchestratorService = new AgentOrchestratorServiceImpl(
                chatSessionService,
                chatGraph,
                routerNode,
                Mockito.mock(MySqlCheckpointer.class),
                new ObjectMapper(),
                Mockito.mock(Mem0Client.class),
                Mockito.mock(RagKnowledgeService.class),
                Runnable::run,
                new ChatAssistantProperties()
        );
    }

    @Test
    void shouldRouteDetectionWriteIntentToDetectionAgent() {
        AgentRouteDecision decision = orchestratorService.decideRoute("帮我开始一批检测");

        assertThat(decision.getTargetAgent()).isEqualTo("DETECTION");
        assertThat(decision.getIntent()).isEqualTo("DETECTION_ACTION");
        assertThat(decision.isConfirmationRequired()).isTrue();
    }

    @Test
    void shouldRouteReportQueryToReportAgent() {
        AgentRouteDecision decision = orchestratorService.decideRoute("给我看今天的检测统计摘要");

        assertThat(decision.getTargetAgent()).isEqualTo("REPORT");
        assertThat(decision.getIntent()).isEqualTo("REPORT_QUERY");
        assertThat(decision.isConfirmationRequired()).isFalse();
    }

    @Test
    void shouldRouteQualityWorkflowQueryToDetectionAgent() {
        AgentRouteDecision decision = orchestratorService.decideRoute("查看待复核质检队列和返工复检任务");

        assertThat(decision.getTargetAgent()).isEqualTo("DETECTION");
        assertThat(decision.getIntent()).isEqualTo("DETECTION_QUERY");
        assertThat(decision.isConfirmationRequired()).isFalse();
    }

    @Test
    void shouldRouteDefectGalleryQueryToDetectionAgent() {
        AgentRouteDecision decision = orchestratorService.decideRoute("查看批次 BATCH-001 的严重缺陷证据");

        assertThat(decision.getTargetAgent()).isEqualTo("DETECTION");
        assertThat(decision.getIntent()).isEqualTo("DETECTION_QUERY");
        assertThat(decision.isConfirmationRequired()).isFalse();
    }

    @Test
    void shouldRouteModelMlopsQueryToResourceAgent() {
        AgentRouteDecision decision = orchestratorService.decideRoute("查看模型评估指标、灰度发布和回滚状态");

        assertThat(decision.getTargetAgent()).isEqualTo("RESOURCE");
        assertThat(decision.getIntent()).isEqualTo("RESOURCE_QUERY");
        assertThat(decision.isConfirmationRequired()).isFalse();
    }

    @Test
    void shouldRouteBusinessMapQuestionToOpsAgent() {
        AgentRouteDecision decision = orchestratorService.decideRoute("模型管理功能在哪个页面");

        assertThat(decision.getTargetAgent()).isEqualTo("OPS");
        assertThat(decision.getIntent()).isEqualTo("OPS_QUERY");
        assertThat(decision.isConfirmationRequired()).isFalse();
    }

    @Test
    void shouldRouteDefectGalleryNavigationQuestionToOpsAgent() {
        AgentRouteDecision decision = orchestratorService.decideRoute("缺陷证据库在哪个页面");

        assertThat(decision.getTargetAgent()).isEqualTo("OPS");
        assertThat(decision.getIntent()).isEqualTo("OPS_QUERY");
        assertThat(decision.isConfirmationRequired()).isFalse();
    }

    @Test
    void shouldRouteAgentDiagnosticsNavigationQuestionToOpsAgent() {
        AgentRouteDecision decision = orchestratorService.decideRoute("Agent健康诊断在哪个页面");

        assertThat(decision.getTargetAgent()).isEqualTo("OPS");
        assertThat(decision.getIntent()).isEqualTo("OPS_QUERY");
        assertThat(decision.isConfirmationRequired()).isFalse();
    }

    @Test
    void shouldVerifySessionOwnerBeforeAppendingUserMessage() {
        ChatSendMessageRequest request = new ChatSendMessageRequest();
        request.setSessionId("sess_other_default");
        request.setContent("查看检测结果");
        Mockito.doThrow(new BusinessException(404, "会话不存在"))
                .when(chatSessionService)
                .verifySessionOwner("admin", "sess_other_default");

        assertThatThrownBy(() -> orchestratorService.handleUserMessage("admin", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("会话不存在");

        Mockito.verify(chatSessionService, Mockito.never())
                .appendUserMessage(Mockito.anyString(), Mockito.anyString());
    }
}
