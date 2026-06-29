package com.ruanzhu.doorhandlecatch.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.config.properties.ChatAssistantProperties;
import com.ruanzhu.doorhandlecatch.config.properties.DeepSeekProperties;
import com.ruanzhu.doorhandlecatch.dto.chat.AgentRouteDecision;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatConfirmActionRequest;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatMessageResponse;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatPendingActionPayload;
import com.ruanzhu.doorhandlecatch.dto.chat.ChatSendMessageRequest;
import com.ruanzhu.doorhandlecatch.entity.ChatPendingAction;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentOrchestratorServiceImplTest {

    private AgentOrchestratorServiceImpl orchestratorService;
    private ChatSessionService chatSessionService;
    private CompiledGraph chatGraph;
    private RagKnowledgeService ragKnowledgeService;
    private Mem0Client mem0Client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        DeepSeekClient deepSeekClient = Mockito.mock(DeepSeekClient.class);
        DeepSeekProperties deepSeekProperties = new DeepSeekProperties();
        ContextBuilder contextBuilder = new ContextBuilder();
        RouterNode routerNode = new RouterNode(deepSeekClient, deepSeekProperties, contextBuilder);
        chatGraph = Mockito.mock(CompiledGraph.class);
        chatSessionService = Mockito.mock(ChatSessionService.class);
        ragKnowledgeService = Mockito.mock(RagKnowledgeService.class);
        mem0Client = Mockito.mock(Mem0Client.class);
        objectMapper = new ObjectMapper();

        orchestratorService = new AgentOrchestratorServiceImpl(
                chatSessionService,
                chatGraph,
                routerNode,
                Mockito.mock(MySqlCheckpointer.class),
                objectMapper,
                mem0Client,
                ragKnowledgeService,
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
    void shouldRouteWorkOrderReworkDispositionToConfirmedDetectionAction() {
        AgentRouteDecision decision = orchestratorService.decideRoute("将工单 WO-20260611-001 标记为返工");

        assertThat(decision.getTargetAgent()).isEqualTo("DETECTION");
        assertThat(decision.getIntent()).isEqualTo("DETECTION_ACTION");
        assertThat(decision.isConfirmationRequired()).isTrue();
    }

    @Test
    void shouldRouteConfirmedDefectMetricQuestionToDetectionQuery() {
        AgentRouteDecision decision = orchestratorService.decideRoute("查看批次 BATCH-001 的确认缺陷数");

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

    @Test
    void shouldAppendKnowledgeSourceWhenRagContextWasUsedForTextAnswer() {
        ChatSendMessageRequest request = new ChatSendMessageRequest();
        request.setSessionId("sess_admin_default");
        request.setContent("首次验收没有质检数据怎么办");

        Mockito.when(ragKnowledgeService.retrieveContext(request.getContent()))
                .thenReturn("[系统知识库检索结果]\n- 来源：常见问题\n业务预置数据导入");
        Mockito.when(mem0Client.searchMemories(Mockito.eq("admin"), Mockito.eq(request.getContent()), Mockito.anyInt()))
                .thenReturn(List.of());

        AgentState result = AgentState.create(request.getSessionId(), request.getContent(), "admin");
        result.set(AgentState.KEY_RESULT_CONTENT, "可以开启业务预置数据后重启后端。");
        result.set(AgentState.KEY_RESULT_TYPE, "TEXT");
        result.set(AgentState.KEY_INTENT, "OPS_QUERY");
        result.set(AgentState.KEY_EXIT_REASON, AgentState.EXIT_COMPLETE);
        Mockito.when(chatGraph.invoke(Mockito.any(AgentState.class))).thenReturn(result);

        ChatMessageResponse response = new ChatMessageResponse();
        response.setSessionId(request.getSessionId());
        response.setMessageType("TEXT");
        response.setContent("可以开启业务预置数据后重启后端。\n\n来源：系统知识库/用户手册");
        Mockito.when(chatSessionService.appendAssistantMessage(
                        Mockito.eq(request.getSessionId()),
                        Mockito.contains("来源：系统知识库/用户手册"),
                        Mockito.eq("TEXT"),
                        Mockito.eq("OPS_QUERY"),
                        Mockito.isNull()
                ))
                .thenReturn(response);

        ChatMessageResponse actual = orchestratorService.handleUserMessage("admin", request);

        assertThat(actual.getContent()).contains("来源：系统知识库/用户手册");
        Mockito.verify(chatSessionService).appendAssistantMessage(
                Mockito.eq(request.getSessionId()),
                Mockito.contains("来源：系统知识库/用户手册"),
                Mockito.eq("TEXT"),
                Mockito.eq("OPS_QUERY"),
                Mockito.isNull()
        );
    }

    @Test
    void shouldRejectAlreadyHandledPendingAction() throws Exception {
        ChatConfirmActionRequest request = new ChatConfirmActionRequest();
        request.setSessionId("sess_admin_default");
        request.setActionId("action-1");
        request.setConfirmed(true);

        ChatPendingActionPayload payload = new ChatPendingActionPayload();
        payload.setIntent("DETECTION_ACTION");

        ChatPendingAction action = new ChatPendingAction();
        action.setSessionId(request.getSessionId());
        action.setActionId(request.getActionId());
        action.setStatus("CONFIRMED");
        action.setActionPayloadJson(objectMapper.writeValueAsString(payload));

        Mockito.when(chatSessionService.getPendingAction(request.getSessionId(), request.getActionId()))
                .thenReturn(action);

        assertThatThrownBy(() -> orchestratorService.confirmAction("admin", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("待确认动作已处理，请勿重复提交");

        Mockito.verify(chatGraph, Mockito.never()).resume(Mockito.anyString(), Mockito.anyMap());
    }
}
