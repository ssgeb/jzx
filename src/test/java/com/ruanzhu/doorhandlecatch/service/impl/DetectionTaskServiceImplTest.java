package com.ruanzhu.doorhandlecatch.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.config.properties.OssProperties;
import com.ruanzhu.doorhandlecatch.dto.detection.CreateDetectionTaskRequest;
import com.ruanzhu.doorhandlecatch.dto.detection.CreateDetectionTaskResponse;
import com.ruanzhu.doorhandlecatch.dto.detection.DetectionCaptureInfo;
import com.ruanzhu.doorhandlecatch.dto.detection.DetectionDispositionRequest;
import com.ruanzhu.doorhandlecatch.dto.detection.DetectionReworkResultRequest;
import com.ruanzhu.doorhandlecatch.dto.detection.DetectionReviewRequest;
import com.ruanzhu.doorhandlecatch.dto.detection.DetectionTaskAssignmentRequest;
import com.ruanzhu.doorhandlecatch.dto.detection.DetectionTaskTraceResponse;
import com.ruanzhu.doorhandlecatch.dto.detection.DetectionTaskProgressResponse;
import com.ruanzhu.doorhandlecatch.dto.detection.DetectionUploadFileRequest;
import com.ruanzhu.doorhandlecatch.dto.detection.DetectionUploadedFileItem;
import com.ruanzhu.doorhandlecatch.dto.detection.event.DetectionTaskFinishedEvent;
import com.ruanzhu.doorhandlecatch.entity.DetectionTask;
import com.ruanzhu.doorhandlecatch.entity.ModelInfo;
import com.ruanzhu.doorhandlecatch.mapper.DetectionTaskMapper;
import com.ruanzhu.doorhandlecatch.mapper.ModelInfoMapper;
import com.ruanzhu.doorhandlecatch.service.ChatSessionService;
import com.ruanzhu.doorhandlecatch.service.DetectionTaskDispatchService;
import com.ruanzhu.doorhandlecatch.service.ModelService;
import com.ruanzhu.doorhandlecatch.service.OssStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DetectionTaskServiceImplTest {

    @Mock
    private DetectionTaskMapper detectionTaskMapper;

    @Mock
    private ModelInfoMapper modelInfoMapper;

    @Mock
    private ModelService modelService;

    @Mock
    private OssStorageService ossStorageService;

    @Mock
    private DetectionTaskDispatchService detectionTaskDispatchService;

    private OssProperties ossProperties;
    private DetectionTaskServiceImpl detectionTaskService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        ossProperties = new OssProperties();
        ossProperties.setUploadUrlExpireMinutes(15);
        ossProperties.setPreviewUrlExpireMinutes(30);
        ossProperties.setBasePrefix("detection");
        detectionTaskService = new DetectionTaskServiceImpl(
                detectionTaskMapper,
                modelInfoMapper,
                modelService,
                ossStorageService,
                ossProperties,
                detectionTaskDispatchService,
                Mockito.mock(ChatSessionService.class),
                new ObjectMapper(),
                new com.ruanzhu.doorhandlecatch.security.DetectionTaskAccessPolicy()
        );
        ReflectionTestUtils.setField(detectionTaskService, "maxImagesPerBatch", 200);
        ReflectionTestUtils.setField(detectionTaskService, "maxImageBytes", 10L * 1024L * 1024L);
    }

    @Test
    void getTaskProgressRejectsTaskOwnedByAnotherUser() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_private");
        task.setCreatedBy("bob");
        when(detectionTaskMapper.selectOne(any())).thenReturn(task);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")))
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> detectionTaskService.getTaskProgress("det_private"));

        assertEquals("无权访问该资源", ex.getMessage());
    }

    @Test
    void createTaskBuildsTraceableOssKeyFromCaptureInfoAndRelativePath() throws Exception {
        DetectionCaptureInfo captureInfo = new DetectionCaptureInfo();
        captureInfo.setCaptureDate("2026-05-20");
        captureInfo.setRegion("上海");
        captureInfo.setCollector("张三");
        captureInfo.setDeviceName("海康Cam01");
        captureInfo.setImageFolderName("批次A");

        DetectionUploadFileRequest file = new DetectionUploadFileRequest();
        file.setFileName("img001.jpg");
        file.setContentType("image/jpeg");
        file.setRelativePath("2026-05-20/上海/张三/海康Cam01/批次A/车间A/相机2/img001.jpg");

        CreateDetectionTaskRequest request = new CreateDetectionTaskRequest();
        request.setTaskType("BATCH");
        request.setCaptureInfo(captureInfo);
        request.setFiles(List.of(file));
        request.setThreshold(BigDecimal.valueOf(0.55));

        ModelInfo modelInfo = new ModelInfo();
        modelInfo.setModelId(7);
        modelInfo.setVersion("v1");

        when(ossStorageService.isConfigured()).thenReturn(true);
        when(ossStorageService.normalizeBasePrefix()).thenReturn("detection");
        when(ossStorageService.generatePutUrl(any(), any(), any())).thenReturn(new URL("http://example.com/upload"));
        when(modelService.getAllModels()).thenReturn(List.of(modelInfo));

        CreateDetectionTaskResponse response = detectionTaskService.createTask(request);

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper).insert(taskCaptor.capture());
        DetectionTask savedTask = taskCaptor.getValue();

        assertEquals("2026-05-20", savedTask.getCaptureDate());
        assertEquals("上海", savedTask.getRegion());
        assertEquals("张三", savedTask.getCollector());
        assertEquals("海康Cam01", savedTask.getDeviceName());
        assertEquals("批次A", savedTask.getImageFolderName());
        assertEquals("detection/2026-05-20/上海/张三/海康Cam01/批次A/Original/", savedTask.getSourceOssPrefix());

        String objectKey = response.getUploadUrls().get(0).getObjectKey();
        assertTrue(objectKey.startsWith(savedTask.getSourceOssPrefix()));
        assertTrue(objectKey.endsWith("车间A/相机2/img001.jpg"));
    }

    @Test
    void createTaskAssignsBatchWorkOrderAndInitialFlowStatus() throws Exception {
        DetectionCaptureInfo captureInfo = new DetectionCaptureInfo();
        captureInfo.setCaptureDate("2026-05-20");
        captureInfo.setRegion("上海");
        captureInfo.setCollector("张三");
        captureInfo.setDeviceName("CAM-01");
        captureInfo.setImageFolderName("批次A");

        DetectionUploadFileRequest file = new DetectionUploadFileRequest();
        file.setFileName("img001.jpg");
        file.setContentType("image/jpeg");

        CreateDetectionTaskRequest request = new CreateDetectionTaskRequest();
        request.setCaptureInfo(captureInfo);
        request.setFiles(List.of(file));

        when(ossStorageService.isConfigured()).thenReturn(true);
        when(ossStorageService.normalizeBasePrefix()).thenReturn("detection");
        when(ossStorageService.generatePutUrl(any(), any(), any())).thenReturn(new URL("http://example.com/upload"));
        when(modelService.getAllModels()).thenReturn(List.of());

        detectionTaskService.createTask(request);

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper).insert(taskCaptor.capture());
        DetectionTask savedTask = taskCaptor.getValue();

        assertEquals("2026-05-20_上海_CAM-01_批次A", savedTask.getBatchNo());
        assertTrue(savedTask.getWorkOrderNo().startsWith("WO-"));
        assertEquals("UPLOADING", savedTask.getFlowStatus());
    }

    @Test
    void createTaskIncrementsModelUsageStatsWhenModelSelected() throws Exception {
        DetectionCaptureInfo captureInfo = new DetectionCaptureInfo();
        captureInfo.setCaptureDate("2026-05-20");
        captureInfo.setRegion("上海");
        captureInfo.setCollector("张三");
        captureInfo.setDeviceName("海康Cam01");
        captureInfo.setImageFolderName("批次A");

        DetectionUploadFileRequest file = new DetectionUploadFileRequest();
        file.setFileName("img001.jpg");
        file.setContentType("image/jpeg");
        file.setRelativePath("img001.jpg");

        CreateDetectionTaskRequest request = new CreateDetectionTaskRequest();
        request.setTaskType("BATCH");
        request.setCaptureInfo(captureInfo);
        request.setFiles(List.of(file));
        request.setModelId(7);

        ModelInfo modelInfo = new ModelInfo();
        modelInfo.setModelId(7);
        modelInfo.setVersion("v1");
        when(ossStorageService.isConfigured()).thenReturn(true);
        when(ossStorageService.normalizeBasePrefix()).thenReturn("detection");
        when(ossStorageService.generatePutUrl(any(), any(), any())).thenReturn(new URL("http://example.com/upload"));
        when(modelInfoMapper.selectByModelId(7)).thenReturn(modelInfo);

        detectionTaskService.createTask(request);

        verify(modelService).incrementUsageStats(eq(7), any(LocalDateTime.class));
    }

    @Test
    void createTaskRejectsOversizedUploadFileBeforeGeneratingUrls() {
        ReflectionTestUtils.setField(detectionTaskService, "maxImageBytes", 4L);
        DetectionCaptureInfo captureInfo = new DetectionCaptureInfo();
        captureInfo.setCaptureDate("2026-05-20");
        captureInfo.setRegion("上海");
        captureInfo.setCollector("张三");
        captureInfo.setDeviceName("CAM-01");
        captureInfo.setImageFolderName("批次A");

        DetectionUploadFileRequest file = new DetectionUploadFileRequest();
        file.setFileName("img001.jpg");
        file.setContentType("image/jpeg");
        file.setFileSize(8L);

        CreateDetectionTaskRequest request = new CreateDetectionTaskRequest();
        request.setCaptureInfo(captureInfo);
        request.setFiles(List.of(file));

        when(ossStorageService.isConfigured()).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> detectionTaskService.createTask(request));

        assertEquals("图片文件不能超过 4B", exception.getMessage());
    }

    @Test
    void createTaskRejectsUnsupportedUploadContentType() {
        DetectionCaptureInfo captureInfo = new DetectionCaptureInfo();
        captureInfo.setCaptureDate("2026-05-20");
        captureInfo.setRegion("上海");
        captureInfo.setCollector("张三");
        captureInfo.setDeviceName("CAM-01");
        captureInfo.setImageFolderName("批次A");

        DetectionUploadFileRequest file = new DetectionUploadFileRequest();
        file.setFileName("img001.jpg");
        file.setContentType("text/plain");

        CreateDetectionTaskRequest request = new CreateDetectionTaskRequest();
        request.setCaptureInfo(captureInfo);
        request.setFiles(List.of(file));

        when(ossStorageService.isConfigured()).thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () -> detectionTaskService.createTask(request));

        assertEquals("图片 Content-Type 不受支持: text/plain", exception.getMessage());
    }

    @Test
    void confirmUploadedRejectsObjectKeyOutsideTaskPrefix() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_001");
        task.setStatus("UPLOADING");
        task.setSourceOssPrefix("detection/2026/Original/");
        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        DetectionUploadedFileItem item = new DetectionUploadedFileItem();
        item.setFileName("img001.jpg");
        item.setObjectKey("other/2026/Original/img001.jpg");

        com.ruanzhu.doorhandlecatch.dto.detection.DetectionTaskUploadedRequest request =
                new com.ruanzhu.doorhandlecatch.dto.detection.DetectionTaskUploadedRequest();
        request.setUploadedFiles(List.of(item));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> detectionTaskService.confirmUploaded("det_001", request)
        );

        assertEquals("上传文件对象 Key 不属于当前任务", exception.getMessage());
    }

    @Test
    void confirmUploadedRejectsBlankUploadedObjectKeys() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_001");
        task.setStatus("UPLOADING");
        task.setSourceOssPrefix("detection/2026/Original/");
        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        DetectionUploadedFileItem blankItem = new DetectionUploadedFileItem();
        blankItem.setFileName("img001.jpg");
        blankItem.setObjectKey(" ");

        com.ruanzhu.doorhandlecatch.dto.detection.DetectionTaskUploadedRequest request =
                new com.ruanzhu.doorhandlecatch.dto.detection.DetectionTaskUploadedRequest();
        request.setUploadedFiles(Arrays.asList(null, blankItem));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> detectionTaskService.confirmUploaded("det_001", request)
        );

        assertEquals("上传文件对象 Key 不能为空", exception.getMessage());
    }

    @Test
    void confirmUploadedDoesNotDispatchTaskAlreadyClaimed() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_uploaded");
        task.setStatus("UPLOADED");
        task.setDispatchId("dispatch-existing");
        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        detectionTaskService.confirmUploaded("det_uploaded", new com.ruanzhu.doorhandlecatch.dto.detection.DetectionTaskUploadedRequest());

        verify(detectionTaskDispatchService, Mockito.never()).dispatchTaskAsync(any());
    }

    @Test
    void confirmUploadedDispatchesOnlyWhenConditionalClaimSucceeds() {
        DetectionTask task = new DetectionTask();
        task.setId(1L);
        task.setTaskId("det_claim");
        task.setStatus("UPLOADING");
        task.setSourceOssPrefix("detection/task/Original/");
        when(detectionTaskMapper.selectOne(any())).thenReturn(task);
        when(detectionTaskMapper.claimUploaded(any())).thenReturn(0);
        DetectionUploadedFileItem item = new DetectionUploadedFileItem();
        item.setFileName("a.jpg");
        item.setObjectKey("detection/task/Original/a.jpg");
        var request = new com.ruanzhu.doorhandlecatch.dto.detection.DetectionTaskUploadedRequest();
        request.setUploadedFiles(List.of(item));

        detectionTaskService.confirmUploaded("det_claim", request);

        verify(detectionTaskDispatchService, Mockito.never()).dispatchTaskAsync(any());
    }

    @Test
    void applyFinishedEventUsesStartedAtFromWorkerEvent() {
        DetectionTask task = new DetectionTask();
        task.setId(1L);
        task.setTaskId("det_123");
        task.setStatus("QUEUED");
        task.setStage("QUEUED");
        task.setTotalImages(1);
        task.setDispatchId("dispatch-1");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        detectionTaskService.applyFinishedEvent(DetectionTaskFinishedEvent.builder()
                .taskId("det_123")
                .eventId("event-1")
                .dispatchId("dispatch-1")
                .status("COMPLETED")
                .resultOssPrefix("detection/task/Result/")
                .resultJsonKey("detection/task/Result/detection_results.json")
                .previewKeys(List.of("detection/task/Result/img001.jpg"))
                .statistics(java.util.Map.of("classCounts", java.util.Map.of("Normal", 1)))
                .totalImages(1)
                .successfulImages(1)
                .failedImages(0)
                .startedAt("2026-05-20T16:12:00+08:00")
                .finishedAt("2026-05-20T16:12:08+08:00")
                .build());

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(taskCaptor.capture());
        DetectionTask updatedTask = taskCaptor.getAllValues().get(taskCaptor.getAllValues().size() - 1);

        assertEquals(LocalDateTime.of(2026, 5, 20, 16, 12, 0), updatedTask.getStartedAt());
        assertEquals(LocalDateTime.of(2026, 5, 20, 16, 12, 8), updatedTask.getFinishedAt());
        assertEquals("PENDING_REVIEW", updatedTask.getFlowStatus());
    }

    @Test
    void applyFinishedEventDoesNotResetReviewedDisposedTaskOnDuplicateEvent() {
        DetectionTask task = new DetectionTask();
        task.setId(1L);
        task.setTaskId("det_done");
        task.setStatus("COMPLETED");
        task.setStage("COMPLETED");
        task.setFlowStatus("RELEASED");
        task.setReviewStatus("REVIEWED");
        task.setReviewConclusion("NORMAL_RELEASE");
        task.setDispositionStatus("DISPOSED");
        task.setDispositionAction("RELEASE");
        task.setDispositionRemark("质检放行");
        task.setDispositionOperator("qa-leader");
        task.setDisposedAt(LocalDateTime.of(2026, 6, 10, 11, 10));
        task.setTotalImages(1);
        task.setDispatchId("dispatch-done");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        detectionTaskService.applyFinishedEvent(DetectionTaskFinishedEvent.builder()
                .taskId("det_done")
                .eventId("event-done")
                .dispatchId("dispatch-done")
                .status("COMPLETED")
                .resultOssPrefix("detection/task/Result/")
                .resultJsonKey("detection/task/Result/detection_results.json")
                .previewKeys(List.of("detection/task/Result/img001.jpg"))
                .statistics(java.util.Map.of("classCounts", java.util.Map.of("Normal", 1)))
                .totalImages(1)
                .successfulImages(1)
                .failedImages(0)
                .finishedAt("2026-06-10T11:30:00+08:00")
                .build());

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(taskCaptor.capture());
        DetectionTask updatedTask = taskCaptor.getAllValues().get(taskCaptor.getAllValues().size() - 1);

        assertEquals("COMPLETED", updatedTask.getStatus());
        assertEquals("RELEASED", updatedTask.getFlowStatus());
        assertEquals("REVIEWED", updatedTask.getReviewStatus());
        assertEquals("DISPOSED", updatedTask.getDispositionStatus());
        assertEquals("RELEASE", updatedTask.getDispositionAction());
        assertEquals("qa-leader", updatedTask.getDispositionOperator());
    }

    @Test
    void applyFinishedEventStoresStructuredDefectEvidence() {
        DetectionTask task = new DetectionTask();
        task.setId(1L);
        task.setTaskId("det_defect");
        task.setStatus("QUEUED");
        task.setStage("QUEUED");
        task.setTotalImages(1);
        task.setDispatchId("dispatch-defect");

        java.util.Map<String, Object> bbox = new java.util.LinkedHashMap<>();
        bbox.put("x", 12);
        bbox.put("y", 18);
        bbox.put("width", 90);
        bbox.put("height", 40);

        java.util.Map<String, Object> defect = new java.util.LinkedHashMap<>();
        defect.put("imageName", "img001.jpg");
        defect.put("sourceKey", "detection/source/img001.jpg");
        defect.put("previewKey", "detection/result/img001.jpg");
        defect.put("defectType", "Rusty");
        defect.put("confidence", 0.92);
        defect.put("area", 3600);
        defect.put("positionRegion", "CENTER");
        defect.put("severityLevel", "MAJOR");
        defect.put("bbox", bbox);

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        detectionTaskService.applyFinishedEvent(DetectionTaskFinishedEvent.builder()
                .taskId("det_defect")
                .eventId("event-defect")
                .dispatchId("dispatch-defect")
                .status("COMPLETED")
                .resultOssPrefix("detection/task/Result/")
                .resultJsonKey("detection/task/Result/detection_results.json")
                .previewKeys(List.of("detection/result/img001.jpg"))
                .statistics(java.util.Map.of("classCounts", java.util.Map.of("Rusty", 1)))
                .defectEvidence(List.of(defect))
                .totalImages(1)
                .successfulImages(1)
                .failedImages(0)
                .build());

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(taskCaptor.capture());
        DetectionTask updatedTask = taskCaptor.getAllValues().get(taskCaptor.getAllValues().size() - 1);

        assertEquals(1, updatedTask.getDefectCount());
        assertEquals("MAJOR", updatedTask.getMaxDefectSeverity());
        assertEquals("Rusty", updatedTask.getPrimaryDefectType());
        assertTrue(updatedTask.getDefectEvidenceJson().contains("\"defectType\":\"Rusty\""));
        assertTrue(updatedTask.getDefectEvidenceJson().contains("\"positionRegion\":\"CENTER\""));
    }

    @Test
    void ignoresFinishedEventFromStaleDispatch() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_stale");
        task.setDispatchId("dispatch-current");
        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        detectionTaskService.applyFinishedEvent(DetectionTaskFinishedEvent.builder()
                .taskId("det_stale")
                .eventId("event-old")
                .dispatchId("dispatch-old")
                .status("COMPLETED")
                .build());

        verify(detectionTaskMapper, Mockito.never()).updateById(any());
    }

    @Test
    void ignoresDuplicateFinishedEvent() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_duplicate");
        task.setDispatchId("dispatch-current");
        task.setLastFinishedEventId("event-1");
        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        detectionTaskService.applyFinishedEvent(DetectionTaskFinishedEvent.builder()
                .taskId("det_duplicate")
                .eventId("event-1")
                .dispatchId("dispatch-current")
                .status("COMPLETED")
                .build());

        verify(detectionTaskMapper, Mockito.never()).updateById(any());
    }

    @Test
    void backgroundFailureCanUpdateTaskWithoutUserSecurityContext() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_background_failure");
        task.setCreatedBy("alice");
        when(detectionTaskMapper.selectOne(any())).thenReturn(task);
        SecurityContextHolder.clearContext();

        detectionTaskService.failTask("det_background_failure", "broker unavailable");

        verify(detectionTaskMapper).updateById(task);
        assertEquals("FAILED", task.getStatus());
        assertEquals("broker unavailable", task.getErrorMessage());
    }

    @Test
    void advanceTaskFlowMovesPendingReviewTaskToConfirmed() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_123");
        task.setStatus("COMPLETED");
        task.setFlowStatus("PENDING_REVIEW");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        DetectionTaskProgressResponse response = detectionTaskService.advanceTaskFlow("det_123", "CONFIRMED");

        assertEquals("CONFIRMED", response.getFlowStatus());
        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper).updateById(taskCaptor.capture());
        assertEquals("CONFIRMED", taskCaptor.getValue().getFlowStatus());
    }

    @Test
    void advanceTaskFlowRejectsDispositionBypass() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_bypass");
        task.setStatus("COMPLETED");
        task.setFlowStatus("CONFIRMED");
        task.setReviewStatus("REVIEWED");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> detectionTaskService.advanceTaskFlow("det_bypass", "RELEASED")
        );

        assertEquals("处置类状态请通过质检处置接口流转", exception.getMessage());
    }

    @Test
    void reviewTaskStoresHumanConclusionAndConfirmsFlow() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_review");
        task.setStatus("COMPLETED");
        task.setFlowStatus("PENDING_REVIEW");

        DetectionReviewRequest request = new DetectionReviewRequest();
        request.setReviewConclusion("CONFIRMED_DEFECT");
        request.setSeverityLevel("MAJOR");
        request.setConfirmedDefectCount(3);
        request.setFalsePositiveCount(1);
        request.setReviewRemark("锈蚀区域明显，1 张疑似误报");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        DetectionTaskProgressResponse response = detectionTaskService.reviewTask("det_review", request);

        assertEquals("REVIEWED", response.getReviewStatus());
        assertEquals("CONFIRMED_DEFECT", response.getReviewConclusion());
        assertEquals("MAJOR", response.getSeverityLevel());
        assertEquals("CONFIRMED", response.getFlowStatus());

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper).updateById(taskCaptor.capture());
        DetectionTask reviewedTask = taskCaptor.getValue();
        assertEquals("REVIEWED", reviewedTask.getReviewStatus());
        assertEquals("CONFIRMED_DEFECT", reviewedTask.getReviewConclusion());
        assertEquals("MAJOR", reviewedTask.getSeverityLevel());
        assertEquals(3, reviewedTask.getConfirmedDefectCount());
        assertEquals(1, reviewedTask.getFalsePositiveCount());
        assertEquals("锈蚀区域明显，1 张疑似误报", reviewedTask.getReviewRemark());
        assertNotNull(reviewedTask.getReviewedAt());
    }

    @Test
    void reviewTaskRejectsNegativeReviewCounts() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_review");
        task.setStatus("COMPLETED");
        task.setFlowStatus("PENDING_REVIEW");

        DetectionReviewRequest request = new DetectionReviewRequest();
        request.setConfirmedDefectCount(-1);
        request.setFalsePositiveCount(0);

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> detectionTaskService.reviewTask("det_review", request)
        );

        assertEquals("复核数量不能为负数", exception.getMessage());
    }

    @Test
    void reviewTaskAcceptsLegacyReviewAliases() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_review_alias");
        task.setStatus("COMPLETED");
        task.setFlowStatus("PENDING_REVIEW");

        DetectionReviewRequest request = new DetectionReviewRequest();
        request.setReviewConclusion("PASS");
        request.setSeverityLevel("LOW");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        DetectionTaskProgressResponse response = detectionTaskService.reviewTask("det_review_alias", request);

        assertEquals("NORMAL_RELEASE", response.getReviewConclusion());
        assertEquals("MINOR", response.getSeverityLevel());
    }

    @Test
    void assignQualityTaskStoresStationOwnerAndMovesToReviewing() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_assign");
        task.setStatus("COMPLETED");
        task.setFlowStatus("PENDING_REVIEW");
        task.setReviewStatus("PENDING");

        DetectionTaskAssignmentRequest request = new DetectionTaskAssignmentRequest();
        request.setQualityStation("QC-STATION-01");
        request.setAssignee("qa-user");
        request.setDueAt("2026-06-11T18:30:00+08:00");
        request.setAssignmentRemark("优先复核锈蚀疑似缺陷");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        DetectionTaskProgressResponse response = detectionTaskService.assignQualityTask("det_assign", request);

        assertEquals("REVIEWING", response.getFlowStatus());
        assertEquals("QC-STATION-01", response.getQualityStation());
        assertEquals("qa-user", response.getAssignee());
        assertEquals("优先复核锈蚀疑似缺陷", response.getAssignmentRemark());

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper).updateById(taskCaptor.capture());
        DetectionTask assignedTask = taskCaptor.getValue();
        assertEquals("QC-STATION-01", assignedTask.getQualityStation());
        assertEquals("qa-user", assignedTask.getAssignee());
        assertEquals("REVIEWING", assignedTask.getFlowStatus());
        assertNotNull(assignedTask.getAssignedAt());
        assertNotNull(assignedTask.getDueAt());
    }

    @Test
    void reviewTaskRejectsDisposedTask() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_review_done");
        task.setStatus("COMPLETED");
        task.setFlowStatus("RELEASED");
        task.setReviewStatus("REVIEWED");
        task.setDispositionStatus("DISPOSED");
        task.setDisposedAt(LocalDateTime.of(2026, 6, 10, 11, 10));

        DetectionReviewRequest request = new DetectionReviewRequest();
        request.setReviewConclusion("CONFIRMED_DEFECT");
        request.setSeverityLevel("MAJOR");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> detectionTaskService.reviewTask("det_review_done", request)
        );

        assertEquals("已处置任务不能重复复核", exception.getMessage());
    }

    @Test
    void disposeTaskStoresQualityDispositionAndMovesToRework() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_dispose");
        task.setStatus("COMPLETED");
        task.setFlowStatus("CONFIRMED");
        task.setReviewStatus("REVIEWED");
        task.setReviewConclusion("CONFIRMED_DEFECT");
        task.setSeverityLevel("MAJOR");

        DetectionDispositionRequest request = new DetectionDispositionRequest();
        request.setDispositionAction("REWORK");
        request.setDispositionRemark("缺陷确认，转返工处理");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        DetectionTaskProgressResponse response = detectionTaskService.disposeTask("det_dispose", request);

        assertEquals("DISPOSED", response.getDispositionStatus());
        assertEquals("REWORK", response.getDispositionAction());
        assertEquals("REWORK_REQUIRED", response.getFlowStatus());
        assertEquals(Boolean.FALSE, response.getRecheckRequired());

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper).updateById(taskCaptor.capture());
        DetectionTask disposedTask = taskCaptor.getValue();
        assertEquals("DISPOSED", disposedTask.getDispositionStatus());
        assertEquals("REWORK", disposedTask.getDispositionAction());
        assertEquals("缺陷确认，转返工处理", disposedTask.getDispositionRemark());
        assertEquals("REWORK_REQUIRED", disposedTask.getFlowStatus());
        assertNotNull(disposedTask.getDisposedAt());
    }

    @Test
    void disposeTaskRejectsReleaseForConfirmedDefect() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_dispose");
        task.setStatus("COMPLETED");
        task.setFlowStatus("CONFIRMED");
        task.setReviewStatus("REVIEWED");
        task.setReviewConclusion("CONFIRMED_DEFECT");
        task.setSeverityLevel("CRITICAL");

        DetectionDispositionRequest request = new DetectionDispositionRequest();
        request.setDispositionAction("RELEASE");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> detectionTaskService.disposeTask("det_dispose", request)
        );

        assertEquals("存在确认缺陷或待复检结论的任务不能直接放行", exception.getMessage());
    }

    @Test
    void disposeTaskRejectsRepeatedDisposition() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_dispose_done");
        task.setStatus("COMPLETED");
        task.setFlowStatus("RELEASED");
        task.setReviewStatus("REVIEWED");
        task.setReviewConclusion("NORMAL_RELEASE");
        task.setDispositionStatus("DISPOSED");
        task.setDispositionAction("RELEASE");
        task.setDisposedAt(LocalDateTime.of(2026, 6, 10, 11, 10));

        DetectionDispositionRequest request = new DetectionDispositionRequest();
        request.setDispositionAction("SCRAP");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> detectionTaskService.disposeTask("det_dispose_done", request)
        );

        assertEquals("任务已完成质检处置，不能重复处置", exception.getMessage());
    }

    @Test
    void submitReworkResultStoresResultAndRoutesToRecheck() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_rework");
        task.setStatus("COMPLETED");
        task.setFlowStatus("REWORK_REQUIRED");
        task.setReviewStatus("REVIEWED");
        task.setDispositionStatus("DISPOSED");
        task.setDispositionAction("REWORK");

        DetectionReworkResultRequest request = new DetectionReworkResultRequest();
        request.setReworkResult("HANDLE_REPLACED");
        request.setReworkOperator("repair-user");
        request.setReworkRemark("已更换门把手锁扣组件");
        request.setRecheckRequired(true);

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        DetectionTaskProgressResponse response = detectionTaskService.submitReworkResult("det_rework", request);

        assertEquals("RECHECK_REQUIRED", response.getFlowStatus());
        assertEquals("HANDLE_REPLACED", response.getReworkResult());
        assertEquals("repair-user", response.getReworkOperator());
        assertEquals(Boolean.TRUE, response.getRecheckRequired());

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper).updateById(taskCaptor.capture());
        DetectionTask reworkedTask = taskCaptor.getValue();
        assertEquals("HANDLE_REPLACED", reworkedTask.getReworkResult());
        assertEquals("repair-user", reworkedTask.getReworkOperator());
        assertEquals("已更换门把手锁扣组件", reworkedTask.getReworkRemark());
        assertEquals("RECHECK_REQUIRED", reworkedTask.getFlowStatus());
        assertEquals("PENDING", reworkedTask.getReviewStatus());
        assertNull(reworkedTask.getDispositionStatus());
        assertNull(reworkedTask.getDispositionAction());
        assertNull(reworkedTask.getDispositionOperator());
        assertNull(reworkedTask.getDisposedAt());
        assertNotNull(reworkedTask.getReworkCompletedAt());
    }

    @Test
    void listQualityQueueReturnsActionableTaskRecords() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_queue");
        task.setStatus("COMPLETED");
        task.setFlowStatus("PENDING_REVIEW");
        task.setReviewStatus("PENDING");
        task.setTotalImages(2);
        task.setCreatedAt(LocalDateTime.of(2026, 6, 10, 10, 0));

        Mockito.doAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<DetectionTask> page = invocation.getArgument(0);
            page.setRecords(List.of(task));
            page.setTotal(1);
            return page;
        }).when(detectionTaskMapper).selectPage(any(), any());

        java.util.Map<String, Object> result = detectionTaskService.listQualityQueue("PENDING_REVIEW", 1, 20);

        assertEquals("PENDING_REVIEW", result.get("queue"));
        assertEquals(1L, result.get("total"));
        @SuppressWarnings("unchecked")
        List<DetectionTaskProgressResponse> records = (List<DetectionTaskProgressResponse>) result.get("records");
        assertEquals(1, records.size());
        assertEquals("det_queue", records.get(0).getTaskId());
        assertEquals("PENDING_REVIEW", records.get(0).getFlowStatus());
    }

    @Test
    void getBatchTraceReportAggregatesBatchInspectionAndQuality() {
        DetectionTask task1 = new DetectionTask();
        task1.setTaskId("det_batch_1");
        task1.setBatchNo("2026-06-11_SH_CAM01_A");
        task1.setWorkOrderNo("WO-1");
        task1.setStatus("COMPLETED");
        task1.setFlowStatus("RELEASED");
        task1.setCaptureDate("2026-06-11");
        task1.setRegion("上海");
        task1.setCollector("collector-a");
        task1.setDeviceName("CAM01");
        task1.setModelId(7);
        task1.setModelVersion("v1");
        task1.setTotalImages(10);
        task1.setProcessedImages(10);
        task1.setSuccessfulImages(9);
        task1.setFailedImages(1);
        task1.setDefectCount(3);
        task1.setConfirmedDefectCount(2);
        task1.setFalsePositiveCount(1);
        task1.setPrimaryDefectType("scratch");
        task1.setMaxDefectSeverity("MAJOR");
        task1.setReviewStatus("REVIEWED");
        task1.setDispositionStatus("DISPOSED");
        task1.setCreatedAt(LocalDateTime.of(2026, 6, 11, 9, 0));
        task1.setFinishedAt(LocalDateTime.of(2026, 6, 11, 9, 10));
        task1.setUpdatedAt(LocalDateTime.of(2026, 6, 11, 9, 20));

        DetectionTask task2 = new DetectionTask();
        task2.setTaskId("det_batch_2");
        task2.setBatchNo("2026-06-11_SH_CAM01_A");
        task2.setWorkOrderNo("WO-2");
        task2.setStatus("COMPLETED");
        task2.setFlowStatus("PENDING_REVIEW");
        task2.setCaptureDate("2026-06-11");
        task2.setRegion("上海");
        task2.setCollector("collector-a");
        task2.setDeviceName("CAM02");
        task2.setModelId(8);
        task2.setModelVersion("v2");
        task2.setTotalImages(5);
        task2.setProcessedImages(5);
        task2.setSuccessfulImages(5);
        task2.setFailedImages(0);
        task2.setDefectCount(0);
        task2.setReviewStatus("PENDING");
        task2.setCreatedAt(LocalDateTime.of(2026, 6, 11, 10, 0));
        task2.setUpdatedAt(LocalDateTime.of(2026, 6, 11, 10, 5));

        when(detectionTaskMapper.selectList(any())).thenReturn(List.of(task1, task2));

        java.util.Map<String, Object> report = detectionTaskService.getBatchTraceReport("2026-06-11_SH_CAM01_A");

        assertEquals("BATCH_TRACE_REPORT", report.get("reportType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> inspection = (Map<String, Object>) report.get("inspection");
        assertEquals(15, inspection.get("totalImages"));
        assertEquals(3, inspection.get("defectCount"));
        assertEquals(0.2, (Double) inspection.get("defectRate"), 0.0001);

        @SuppressWarnings("unchecked")
        Map<String, Object> quality = (Map<String, Object>) report.get("quality");
        assertEquals(1L, quality.get("pendingReview"));
        assertEquals(1L, quality.get("reviewed"));
        assertEquals(0.5, (Double) quality.get("reviewCompletionRate"), 0.0001);

        @SuppressWarnings("unchecked")
        Map<String, Object> distribution = (Map<String, Object>) report.get("distribution");
        @SuppressWarnings("unchecked")
        Map<String, Long> deviceDistribution = (Map<String, Long>) distribution.get("device");
        assertEquals(1L, deviceDistribution.get("CAM01"));
        assertEquals(1L, deviceDistribution.get("CAM02"));

        @SuppressWarnings("unchecked")
        List<DetectionTaskProgressResponse> records = (List<DetectionTaskProgressResponse>) report.get("records");
        assertEquals(2, records.size());
        assertEquals("det_batch_1", records.get(0).getTaskId());
    }

    @Test
    void getWorkOrderTraceReportAggregatesAcrossBatches() {
        DetectionTask task1 = new DetectionTask();
        task1.setTaskId("det_work_order_1");
        task1.setBatchNo("BATCH-A");
        task1.setWorkOrderNo("WO-20260611-001");
        task1.setStatus("COMPLETED");
        task1.setFlowStatus("REWORK_REQUIRED");
        task1.setRegion("上海");
        task1.setCollector("collector-a");
        task1.setDeviceName("CAM01");
        task1.setModelId(7);
        task1.setModelVersion("v1");
        task1.setTotalImages(12);
        task1.setSuccessfulImages(12);
        task1.setDefectCount(4);
        task1.setConfirmedDefectCount(3);
        task1.setReviewStatus("REVIEWED");
        task1.setDispositionStatus("PENDING");
        task1.setCreatedAt(LocalDateTime.of(2026, 6, 11, 9, 0));
        task1.setUpdatedAt(LocalDateTime.of(2026, 6, 11, 9, 30));

        DetectionTask task2 = new DetectionTask();
        task2.setTaskId("det_work_order_2");
        task2.setBatchNo("BATCH-B");
        task2.setWorkOrderNo("WO-20260611-001");
        task2.setStatus("COMPLETED");
        task2.setFlowStatus("RELEASED");
        task2.setRegion("苏州");
        task2.setCollector("collector-b");
        task2.setDeviceName("CAM02");
        task2.setModelId(7);
        task2.setModelVersion("v1");
        task2.setTotalImages(8);
        task2.setSuccessfulImages(8);
        task2.setDefectCount(0);
        task2.setReviewStatus("REVIEWED");
        task2.setDispositionStatus("DISPOSED");
        task2.setCreatedAt(LocalDateTime.of(2026, 6, 11, 10, 0));
        task2.setUpdatedAt(LocalDateTime.of(2026, 6, 11, 10, 30));

        when(detectionTaskMapper.selectList(any())).thenReturn(List.of(task1, task2));

        java.util.Map<String, Object> report = detectionTaskService.getWorkOrderTraceReport("WO-20260611-001");

        assertEquals("WORK_ORDER_TRACE_REPORT", report.get("reportType"));
        assertEquals("WO-20260611-001", report.get("workOrderNo"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) report.get("summary");
        assertEquals(2, summary.get("taskCount"));
        @SuppressWarnings("unchecked")
        List<String> batchNos = (List<String>) summary.get("batchNos");
        assertEquals(List.of("BATCH-A", "BATCH-B"), batchNos);

        @SuppressWarnings("unchecked")
        Map<String, Object> inspection = (Map<String, Object>) report.get("inspection");
        assertEquals(20, inspection.get("totalImages"));
        assertEquals(4, inspection.get("defectCount"));

        @SuppressWarnings("unchecked")
        Map<String, Object> quality = (Map<String, Object>) report.get("quality");
        assertEquals(1L, quality.get("disposed"));
        assertEquals(1L, quality.get("reworkRequired"));
        assertEquals(1L, quality.get("closed"));
        assertEquals(0.5, (Double) quality.get("closureRate"), 0.0001);
    }

    @Test
    void listQualityQueueRejectsUnsupportedQueue() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> detectionTaskService.listQualityQueue("UNKNOWN", 1, 20)
        );

        assertEquals("不支持的质检队列: UNKNOWN", exception.getMessage());
    }

    @Test
    void listDefectGalleryReturnsTraceableDefectRecords() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_gallery");
        task.setStatus("COMPLETED");
        task.setFlowStatus("PENDING_REVIEW");
        task.setBatchNo("BATCH-001");
        task.setWorkOrderNo("WO-001");
        task.setDeviceName("CAM-01");
        task.setModelId(7);
        task.setModelVersion("v2");
        task.setDefectEvidenceJson("[{\"imageName\":\"img001.jpg\",\"defectType\":\"Rusty\",\"confidence\":0.92,\"area\":3600,\"positionRegion\":\"CENTER\",\"severityLevel\":\"MAJOR\"}]");
        task.setDefectCount(1);
        task.setPrimaryDefectType("Rusty");
        task.setMaxDefectSeverity("MAJOR");
        task.setCreatedAt(LocalDateTime.of(2026, 6, 10, 10, 0));

        Mockito.doAnswer(invocation -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<DetectionTask> page = invocation.getArgument(0);
            page.setRecords(List.of(task));
            page.setTotal(1);
            return page;
        }).when(detectionTaskMapper).selectPage(any(), any());

        java.util.Map<String, Object> result = detectionTaskService.listDefectGallery(
                "Rusty", "MAJOR", "CAM-01", "BATCH-001", 7, 1, 20
        );

        assertEquals(1L, result.get("total"));
        assertEquals("Rusty", result.get("defectType"));
        assertEquals("MAJOR", result.get("severityLevel"));
        @SuppressWarnings("unchecked")
        List<DetectionTaskTraceResponse> records = (List<DetectionTaskTraceResponse>) result.get("records");
        assertEquals(1, records.size());
        assertEquals("det_gallery", records.get(0).getTaskId());
        assertEquals(1, records.get(0).getDefectCount());
        assertEquals("Rusty", records.get(0).getPrimaryDefectType());
        assertEquals(1, records.get(0).getDefectEvidence().size());
    }

    @Test
    void retryTaskResetsFailedTaskAndDispatchesAgain() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_retry");
        task.setStatus("FAILED");
        task.setStage("FAILED");
        task.setFlowStatus("FAILED");
        task.setOriginalImageKeysJson("[\"detection/source/img001.jpg\"]");
        task.setResultJsonOssKey("detection/result/result.json");
        task.setPreviewImageKeysJson("[\"detection/result/img001.jpg\"]");
        task.setStatisticsJson("{\"classCounts\":{\"Rusty\":1}}");
        task.setErrorMessage("worker timeout");
        task.setReviewStatus("REVIEWED");
        task.setDispositionStatus("DISPOSED");
        task.setRecheckRequired(true);
        task.setStartedAt(LocalDateTime.of(2026, 6, 10, 10, 0));
        task.setFinishedAt(LocalDateTime.of(2026, 6, 10, 10, 1));

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        DetectionTaskProgressResponse response = detectionTaskService.retryTask("det_retry");

        assertEquals("UPLOADED", response.getStatus());
        assertEquals("PENDING_DETECTION", response.getFlowStatus());
        assertEquals("重新检测任务已提交", response.getMessage());

        ArgumentCaptor<DetectionTask> taskCaptor = ArgumentCaptor.forClass(DetectionTask.class);
        verify(detectionTaskMapper).updateById(taskCaptor.capture());
        DetectionTask retriedTask = taskCaptor.getValue();
        assertEquals("UPLOADED", retriedTask.getStatus());
        assertEquals("UPLOADED", retriedTask.getStage());
        assertEquals("PENDING_DETECTION", retriedTask.getFlowStatus());
        assertEquals("PENDING", retriedTask.getReviewStatus());
        assertEquals("PENDING", retriedTask.getDispositionStatus());
        assertEquals(Boolean.FALSE, retriedTask.getRecheckRequired());
        assertEquals(0, retriedTask.getProcessedImages());
        assertEquals(0, retriedTask.getSuccessfulImages());
        assertEquals(0, retriedTask.getFailedImages());
        assertEquals(null, retriedTask.getResultJsonOssKey());
        assertEquals(null, retriedTask.getPreviewImageKeysJson());
        assertEquals(null, retriedTask.getStatisticsJson());
        assertEquals(null, retriedTask.getErrorMessage());
        assertEquals(null, retriedTask.getStartedAt());
        assertEquals(null, retriedTask.getFinishedAt());
        verify(detectionTaskDispatchService).dispatchTaskAsync("det_retry");
    }

    @Test
    void retryTaskRejectsNonActionableTask() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_done");
        task.setStatus("COMPLETED");
        task.setFlowStatus("RELEASED");
        task.setOriginalImageKeysJson("[\"detection/source/img001.jpg\"]");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> detectionTaskService.retryTask("det_done")
        );

        assertEquals("当前任务状态不支持重新检测", exception.getMessage());
    }

    @Test
    void markUploadedStoresUploadedKeysAndDispatchesDetection() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_mark_uploaded");
        task.setStatus("UPLOADING");
        task.setStage("UPLOADING");
        task.setFlowStatus("UPLOADING");
        task.setOriginalImageKeysJson("[\"old/key.jpg\"]");
        task.setTotalImages(1);

        DetectionUploadedFileItem uploaded = new DetectionUploadedFileItem();
        uploaded.setFileName("img001.jpg");
        uploaded.setObjectKey("detection/source/img001.jpg");

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);
        when(detectionTaskMapper.claimUploaded(any())).thenReturn(1);

        detectionTaskService.markUploaded("det_mark_uploaded", List.of(uploaded));

        verify(detectionTaskMapper).claimUploaded(any());
        DetectionTask updatedTask = task;

        assertEquals("UPLOADED", updatedTask.getStatus());
        assertEquals("UPLOADED", updatedTask.getStage());
        assertEquals("PENDING_DETECTION", updatedTask.getFlowStatus());
        assertEquals(1, updatedTask.getTotalImages());
        assertTrue(updatedTask.getOriginalImageKeysJson().contains("detection/source/img001.jpg"));
        assertNotNull(updatedTask.getDispatchId());
        verify(detectionTaskDispatchService).dispatchTaskAsync("det_mark_uploaded");
    }

    @Test
    void getQualityReportBuildsReportReadyTaskSnapshot() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_report");
        task.setWorkflowUuid("wf-report");
        task.setBatchNo("BATCH-20260610");
        task.setWorkOrderNo("WO-20260610-001");
        task.setStatus("COMPLETED");
        task.setFlowStatus("RELEASED");
        task.setModelId(7);
        task.setModelVersion("v2");
        task.setCaptureDate("2026-06-10");
        task.setRegion("上海");
        task.setCollector("张三");
        task.setDeviceName("CAM-01");
        task.setImageFolderName("门把手批次A");
        task.setTotalImages(10);
        task.setSuccessfulImages(9);
        task.setFailedImages(1);
        task.setStatisticsJson("{\"classCounts\":{\"Rusty\":2},\"missDetectionRate\":0.01}");
        task.setReviewStatus("REVIEWED");
        task.setReviewConclusion("NORMAL_RELEASE");
        task.setSeverityLevel("MINOR");
        task.setConfirmedDefectCount(0);
        task.setFalsePositiveCount(2);
        task.setReviewRemark("两处为误报，准予放行");
        task.setReviewer("qa-user");
        task.setReviewedAt(LocalDateTime.of(2026, 6, 10, 11, 0));
        task.setDispositionStatus("DISPOSED");
        task.setDispositionAction("RELEASE");
        task.setDispositionRemark("质检放行");
        task.setDispositionOperator("leader");
        task.setDisposedAt(LocalDateTime.of(2026, 6, 10, 11, 10));
        task.setOriginalImageKeysJson("[\"detection/source/img001.jpg\"]");
        task.setPreviewImageKeysJson("[\"detection/result/img001.jpg\"]");
        task.setResultJsonOssKey("detection/result/result.json");
        task.setCreatedAt(LocalDateTime.of(2026, 6, 10, 10, 0));
        task.setStartedAt(LocalDateTime.of(2026, 6, 10, 10, 2));
        task.setFinishedAt(LocalDateTime.of(2026, 6, 10, 10, 8));

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);
        when(ossStorageService.isConfigured()).thenReturn(true);

        java.util.Map<String, Object> report = detectionTaskService.getQualityReport("det_report");

        assertEquals("QUALITY_INSPECTION_REPORT", report.get("reportType"));
        assertEquals("det_report", report.get("taskId"));
        assertEquals("WO-20260610-001", report.get("workOrderNo"));
        assertEquals("RELEASED", report.get("flowStatus"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> inspection = (java.util.Map<String, Object>) report.get("inspection");
        assertEquals(10, inspection.get("totalImages"));
        assertEquals(9, inspection.get("successfulImages"));
        assertEquals(1, inspection.get("failedImages"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> review = (java.util.Map<String, Object>) report.get("review");
        assertEquals("REVIEWED", review.get("reviewStatus"));
        assertEquals("NORMAL_RELEASE", review.get("reviewConclusion"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> disposition = (java.util.Map<String, Object>) report.get("disposition");
        assertEquals("DISPOSED", disposition.get("dispositionStatus"));
        assertEquals("RELEASE", disposition.get("dispositionAction"));

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> evidence = (java.util.Map<String, Object>) report.get("evidence");
        assertEquals(1, ((List<?>) evidence.get("originalImages")).size());
        assertEquals(1, ((List<?>) evidence.get("previewImages")).size());
        assertTrue(((String) evidence.get("resultJsonUrl")).contains("detection%2Fresult%2Fresult.json"));

        assertEquals(5, ((List<?>) report.get("timeline")).size());
    }

    @Test
    void getTaskTraceBuildsEvidenceChainForFinishedReviewedTask() {
        DetectionTask task = new DetectionTask();
        task.setTaskId("det_trace");
        task.setWorkflowUuid("wf-001");
        task.setBatchNo("BATCH-001");
        task.setWorkOrderNo("WO-20260610-001");
        task.setStatus("COMPLETED");
        task.setFlowStatus("CONFIRMED");
        task.setModelId(7);
        task.setModelVersion("v2");
        task.setCaptureDate("2026-06-10");
        task.setRegion("上海");
        task.setCollector("张三");
        task.setDeviceName("CAM-01");
        task.setImageFolderName("门把手批次A");
        task.setSourceOssPrefix("detection/source/");
        task.setResultOssPrefix("detection/result/");
        task.setResultJsonOssKey("detection/result/detection_results.json");
        task.setOriginalImageKeysJson("[\"detection/source/img001.jpg\",\"detection/source/img002.jpg\"]");
        task.setPreviewImageKeysJson("[\"detection/result/img001.jpg\"]");
        task.setDefectEvidenceJson("[{\"imageName\":\"img001.jpg\",\"defectType\":\"Rusty\",\"confidence\":0.92,\"area\":3600,\"positionRegion\":\"CENTER\",\"severityLevel\":\"MAJOR\",\"bbox\":{\"x\":12,\"y\":18,\"width\":90,\"height\":40}}]");
        task.setDefectCount(1);
        task.setPrimaryDefectType("Rusty");
        task.setMaxDefectSeverity("MAJOR");
        task.setReviewStatus("REVIEWED");
        task.setReviewConclusion("CONFIRMED_DEFECT");
        task.setSeverityLevel("CRITICAL");
        task.setConfirmedDefectCount(2);
        task.setFalsePositiveCount(1);
        task.setReviewRemark("锈蚀严重，保留一张误报样本");
        task.setReviewer("qa-user");
        task.setCreatedAt(LocalDateTime.of(2026, 6, 10, 9, 0));
        task.setStartedAt(LocalDateTime.of(2026, 6, 10, 9, 5));
        task.setFinishedAt(LocalDateTime.of(2026, 6, 10, 9, 8));
        task.setReviewedAt(LocalDateTime.of(2026, 6, 10, 9, 20));

        when(detectionTaskMapper.selectOne(any())).thenReturn(task);
        when(ossStorageService.isConfigured()).thenReturn(true);

        DetectionTaskTraceResponse response = detectionTaskService.getTaskTrace("det_trace");

        assertEquals("det_trace", response.getTaskId());
        assertEquals("BATCH-001", response.getBatchNo());
        assertEquals("WO-20260610-001", response.getWorkOrderNo());
        assertEquals("CAM-01", response.getDeviceName());
        assertEquals(7, response.getModelId());
        assertEquals("v2", response.getModelVersion());
        assertEquals(2, response.getOriginalImages().size());
        assertTrue(response.getOriginalImages().get(0).getPreviewUrl().contains("detection%2Fsource%2Fimg001.jpg"));
        assertEquals("detection/result/detection_results.json", response.getResultJsonKey());
        assertTrue(response.getResultJsonUrl().contains("detection%2Fresult%2Fdetection_results.json"));
        assertEquals("CONFIRMED_DEFECT", response.getReviewConclusion());
        assertEquals("CRITICAL", response.getSeverityLevel());
        assertEquals(1, response.getDefectEvidence().size());
        assertEquals("Rusty", response.getDefectEvidence().get(0).get("defectType"));
        assertEquals("CENTER", response.getDefectEvidence().get(0).get("positionRegion"));
        assertEquals(1, response.getDefectCount());
        assertEquals("Rusty", response.getPrimaryDefectType());
        assertEquals("MAJOR", response.getMaxDefectSeverity());
        assertEquals(2, response.getConfirmedDefectCount());
        assertEquals(1, response.getFalsePositiveCount());
        assertEquals(4, response.getTimeline().size());
    }
}
