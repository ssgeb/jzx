# ONNX 模型治理与质量追溯闭环代码实现详解

## 一、亮点简历写法

围绕 ONNX 模型构建上传校验、版本管理、指标评估、部署策略、使用统计、回滚归档等生命周期能力，并将推理结果接入“检测—分派—复核—处置—返工—复检—报告”质量闭环，实现模型、缺陷证据、批次和工单全过程可追踪。

## 二、整体架构：模型生命周期 + 质量状态机

```text
模型文件 → 格式/大小校验 → ONNX Runtime 验证 → READY
                                               │
             评估指标/部署策略/发布/归档 ← ModelInfo
                                               │ 被任务引用
                                               ▼
图片检测 → 缺陷证据 → 质检分派 → 人工复核 → 处置
                                      │          ├─ ACCEPT/CLOSED
                                      │          ├─ HOLD
                                      │          └─ REWORK/RECHECK
                                      └────────────→ 返工回填/复检
                                               │
                                               ▼
                                    批次报告、工单报告、时间线
```

## 三、代码位置索引

| 模块 | 文件 | 作用 |
| --- | --- | --- |
| 模型上传 | `ModelServiceImpl.java` | 文件保存、验证、状态和日志 |
| 模型验证 | `OnnxModelValidationService.java` | ONNX Runtime 加载验证 |
| 模型治理 | `ModelInfoServiceImpl.java` | 指标和部署策略 |
| 模型实体 | `ModelInfo.java` | 版本、状态、指标、策略 |
| 任务实体 | `DetectionTask.java` | 检测和质量全字段 |
| 质量服务 | `DetectionTaskServiceImpl.java` | 分派、复核、处置、返工 |
| 数据迁移 | `migration-V6-model-mlops.sql` | MLOps 字段 |
| 质量迁移 | `migration-V8-quality-disposition.sql` | 处置字段 |

## 四、详细流程图

### 4.1 模型上传与发布

```text
Step 1  校验扩展名=.onnx、大小不超过限制
   ↓
Step 2  保存到受控模型目录
   ↓
Step 3  ONNX Runtime 创建 Session 验证结构
   ├─ PASSED → status=READY
   └─ FAILED → status=DRAFT, isDefault=false
   ↓
Step 4  保存 version、creator、validationMessage
   ↓
Step 5  记录 ModelOperationLog(UPLOAD/VALIDATE)
   ↓
Step 6  配置评估指标和 FULL/CANARY/A-B/ROLLBACK 元数据
```

### 4.2 质量闭环

```text
COMPLETED/PARTIAL_FAILED
   ↓ assignQualityTask
REVIEWING（站点、责任人、截止时间）
   ↓ reviewTask
CONFIRMED（结论、严重度、确认缺陷、误报）
   ↓ disposeTask
   ├─ ACCEPT → CLOSED
   ├─ HOLD → HOLD
   ├─ REWORK → REWORK_REQUIRED
   └─ RECHECK → RECHECK_REQUIRED
                    ↓ submitReworkResult
           PENDING_REVIEW / RECHECK_REQUIRED
```

### 4.3 追溯时间线

```text
CREATED → DISPATCHED → FINISHED → ASSIGNED
        → REVIEWED → DISPOSED → REWORK_COMPLETED
```

每个事件保存时间、操作人和描述，可按 `taskId`、`batchNo`、`workOrderNo` 生成报告。

## 五、关键机制详解

### 5.1 模型结果为什么必须固化版本

任务创建时保存 `modelId`、`modelVersion` 和 `threshold`。即使默认模型后来升级，历史任务仍能回答“当时使用哪个模型和阈值”，这是质量归因的前提。

### 5.2 模型指标与部署策略的边界

代码已经持久化 Precision、Recall、mAP、F1、推理耗时、灰度比例、A/B 分组和回滚来源；自动按比例分流属于后续优化，不能在面试中描述为已完整实现的流量平台。

### 5.3 缺陷证据

Worker 返回缺陷类型、置信度、面积、位置、严重度、Bounding Box、原图 Key 和标注图 Key。质量结论因此能追溯到具体图片和具体检测框。

## 六、代码详解

### 6.1 上传时验证并决定模型状态

文件：`ModelServiceImpl.insertWithRetry()`

```java
ValidationResult validation = modelValidationService.validate(destFile.toPath());
modelInfo.setStatus("PASSED".equals(validation.status()) ? "READY" : "DRAFT");
modelInfo.setValidationStatus(validation.status());
modelInfo.setValidationMessage(validation.message());
modelInfo.setMlopsStatus("UNASSESSED");
modelInfo.setDeploymentStrategy("FULL");
modelInfo.setCanaryPercent(100);
```

逐句解释：

1. 文件落盘后由 Runtime 验证，而不是只看扩展名。
2. 通过的模型可进入待发布状态，失败模型留在草稿态。
3. 验证结果和原因被持久化，前端可明确展示失败原因。
4. 新模型默认未评估、全量策略元数据为 100%，但发布仍由业务操作控制。

### 6.2 人工复核状态校验

文件：`DetectionTaskServiceImpl.reviewTask()`

```java
if (!"COMPLETED".equals(task.getStatus())
        && !"PARTIAL_FAILED".equals(task.getStatus())) {
    throw new BusinessException("只有已完成检测任务才能复核");
}
if (isQualityDisposed(task)) {
    throw new BusinessException("已处置任务不能重复复核");
}
task.setReviewStatus("REVIEWED");
task.setReviewer(resolveCurrentUsername());
task.setFlowStatus("CONFIRMED");
```

逐句解释：

1. 只有已产生检测结果的任务可复核。
2. 已完成处置的任务不能被覆盖，保护审计链。
3. 操作人取自认证上下文，不信任前端传值。
4. 业务流转到 `CONFIRMED` 后才能继续处置。

### 6.3 处置分支

```java
task.setDispositionStatus("DISPOSED");
task.setDispositionAction(action);
task.setDispositionOperator(resolveCurrentUsername());
task.setRecheckRequired("RECHECK".equals(action)
        || Boolean.TRUE.equals(request.getRecheckRequired()));
task.setFlowStatus(resolveDispositionFlowStatus(
        action, task.getRecheckRequired()));
```

逐句解释：

1. 处置本身与具体动作分字段保存，便于统计。
2. 操作人和时间构成审计依据。
3. 是否复检由动作和显式请求共同决定。
4. 状态解析集中在方法中，避免 Controller 各自拼状态。

### 6.4 返工回填后重置质量阶段

```java
task.setReworkResult(request.getReworkResult().trim());
task.setReviewStatus("PENDING");
task.setDispositionStatus(null);
task.setDispositionAction(null);
task.setFlowStatus(recheckRequired
        ? "RECHECK_REQUIRED" : "PENDING_REVIEW");
```

逐句解释：

1. 保存返工结果但不沿用旧复核结论。
2. 清空旧处置字段，防止界面误判已经闭环。
3. 根据是否需要复检进入不同队列，形成新的质量循环。

### 6.5 时间线生成

```java
addTraceEvent(events, "CREATED", "任务创建", task.getCreatedAt(),
        task.getCreatedBy(), "生成批次与工单");
addTraceEvent(events, "REVIEWED", "人工复核", task.getReviewedAt(),
        task.getReviewer(), "结论 " + task.getReviewConclusion());
addTraceEvent(events, "DISPOSED", "质检处置", task.getDisposedAt(),
        task.getDispositionOperator(), "动作 " + task.getDispositionAction());
```

逐句解释：时间线不是额外日志表，而是从各阶段持久化字段确定性组装；时间为空的阶段不会被添加，因此能反映真实进度。

## 七、状态与指标汇总

| 类别 | 字段/状态 | 作用 |
| --- | --- | --- |
| 模型质量 | Precision/Recall/mAP/F1 | 评估准确性 |
| 模型性能 | avgInferenceMs | 评估推理时延 |
| 模型策略 | FULL/CANARY/A-B/ROLLBACK | 记录部署意图 |
| 检测状态 | COMPLETED/PARTIAL_FAILED/FAILED | 推理结果 |
| 质量状态 | REVIEWING/CONFIRMED/CLOSED | 人工流程 |
| 异常处置 | HOLD/REWORK/RECHECK | 后续动作 |

## 八、关键设计总结

| 特性 | 实现方式 | 代码位置 |
| --- | --- | --- |
| 模型准入 | ONNX Runtime 实际加载 | `OnnxModelValidationService` |
| 版本追溯 | 任务固化模型版本和阈值 | `DetectionTask` |
| 指标治理 | MLOps 指标字段 | `ModelInfo` |
| 状态保护 | Service 前置状态校验 | `DetectionTaskServiceImpl` |
| 缺陷证据 | bbox + OSS 原图/标注图 | Finished 事件 |
| 全链路报告 | 批次、工单、时间线 | Trace Report 方法 |

## 九、面试问题与答案

### 1. 为什么模型上传后不能直接发布？

扩展名正确不代表模型可运行，必须通过 ONNX Runtime 结构验证，并记录输入输出兼容性。

### 2. 为什么还需要人工复核？

工业质检存在误报和漏报成本，模型负责初筛，人工负责最终责任判定；误报数据还能反哺模型评估。

### 3. 如何定位历史质量问题？

任务固化采集信息、模型版本、阈值、缺陷证据和每次人工操作，再按批次或工单聚合时间线。

### 4. 灰度发布是否已经完全自动化？

当前实现了策略、比例、A/B 分组和回滚来源的管理及校验，自动流量路由仍是可继续建设的部分。

### 5. 为什么返工后要清空旧处置？

返工后的产品状态已发生变化，旧复核和处置不能代表新结果，必须重新进入复核或复检队列。
