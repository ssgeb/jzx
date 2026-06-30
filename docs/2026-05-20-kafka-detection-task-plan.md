# Kafka Detection Task Plan

**Goal:** 将当前“OSS 直传 + Java 编排 + FastAPI 检测”的链路升级为“OSS + MySQL + Kafka + FastAPI”的异步任务架构，并保留可追踪的采集目录信息、检测时间、结果 JSON 路径。

Implementation plan: `docs/2026-05-20-kafka-detection-task-implementation-plan.md`

**Current Decision:** 本地批量图片目录按固定结构解析：

```text
采集日期/地区/采集员/采集设备/图片或子目录...
```

其中：

- `采集时间` = 文件夹第一层业务日期
- `检测开始时间` = FastAPI 开始执行检测前后，由 Java 落库
- `检测结束时间` = FastAPI 完成任务并返回结果事件后，由 Java 落库

---

## 1. Architecture

系统拆分为 5 个稳定职责：

1. `前端`
   - 选择本地文件夹
   - 解析 `采集日期 / 地区 / 采集员 / 采集设备`
   - 允许用户人工确认和修改
   - 使用 OSS 预签名地址直传原图
   - 通过 Java 查询任务状态与结果

2. `Spring Boot`
   - 创建检测任务
   - 生成 OSS 上传地址
   - 将任务元数据落库到 MySQL
   - 在上传确认后发送 Kafka 任务消息
   - 消费 Kafka 结果消息
   - 更新任务状态、结果路径和检测时间

3. `OSS`
   - 保存原图
   - 保存标注图
   - 保存 `detection_results.json`

4. `Kafka`
   - 传递待检测任务事件
   - 传递检测完成结果事件
   - 可选传递检测进度事件

5. `FastAPI`
   - 消费检测任务事件
   - 执行目标检测
   - 将检测结果写回 OSS
   - 发送检测完成事件到 Kafka

核心原则：

- 大文件只放在 `OSS`
- 任务索引、状态、业务检索信息放在 `MySQL`
- 异步调度和解耦通过 `Kafka`
- Kafka 只传任务元数据与结果元数据，不承载大文件结果

---

## 2. OSS Path Rules

批量原图路径统一按业务路径组织，便于人工查找：

```text
{base-prefix}/{captureDate}/{region}/{collector}/{deviceName}/{uploadTimestamp}_{taskId}/originals/{relativePath}
```

示例：

```text
detection/2026-05-20/上海/张三/海康Cam01/20260520_161000_det_ab12cd34/originals/车间A/相机2/img001.jpg
```

结果文件建议路径：

```text
{base-prefix}/{captureDate}/{region}/{collector}/{deviceName}/{uploadTimestamp}_{taskId}/results/
```

结果 JSON 建议路径：

```text
{result-prefix}/detection_results.json
```

示例：

```text
detection/2026-05-20/上海/张三/海康Cam01/20260520_161000_det_ab12cd34/results/detection_results.json
```

---

## 3. MySQL Responsibilities

MySQL 不保存图片本体，主要负责：

1. 任务台账
2. 任务状态查询
3. 业务字段检索
4. 结果 JSON 索引入口
5. 检测时间审计

建议主表：`detection_task`

### 3.1 Required Fields

- `task_id`
- `capture_date`
- `region`
- `collector`
- `device_name`
- `source_oss_prefix`
- `result_oss_prefix`
- `result_json_oss_key`
- `status`
- `stage`
- `model_id`
- `model_version`
- `threshold`
- `total_images`
- `processed_images`
- `successful_images`
- `failed_images`
- `error_message`
- `created_at`
- `started_at`
- `finished_at`
- `updated_at`

### 3.2 Field Semantics

- `created_at`
  - Java 创建任务时间
- `started_at`
  - Java 将任务真正送入检测阶段并开始调度 FastAPI 的时间
- `finished_at`
  - FastAPI 完成整个检测任务并返回结果事件后，由 Java 写入的完成时间
- `result_json_oss_key`
  - 结果明细文件入口，是后续查询和归档的重要索引字段

### 3.3 Optional Future Fields

- `kafka_message_key`
- `retry_count`
- `last_event_id`
- `last_event_time`
- `timeout_at`

### 3.4 Optional Future Child Table

若未来需要“按单张图片查历史、做横向统计”，可增加：

`detection_task_item`

建议字段：

- `task_id`
- `file_name`
- `object_key`
- `annotated_key`
- `status`
- `category`
- `confidence`
- `capture_date`
- `detected_at`

当前阶段不是必须，可先用 `detection_results.json` 保存逐图明细。

---

## 4. Kafka Topics

推荐先使用 2 个主 Topic：

1. `detection.task.created`
   - Java 发送
   - FastAPI 消费

2. `detection.task.finished`
   - FastAPI 发送
   - Java 消费

可选第 3 个：

3. `detection.task.progress`
   - FastAPI 发送
   - Java 消费
   - 用于更细的进度展示

Kafka `message key` 统一使用：

```text
taskId
```

这样可以保证同一任务天然按 key 保序，方便幂等处理。

---

## 5. Kafka Message Schemas

### 5.1 detection.task.created

```json
{
  "eventId": "evt_001",
  "eventType": "DETECTION_TASK_CREATED",
  "eventTime": "2026-05-20T16:10:00+08:00",
  "taskId": "det_20260520_xxx",
  "bucketName": "your-bucket",
  "sourcePrefix": "detection/2026-05-20/上海/张三/设备A/20260520_161000_det_xxx/originals/",
  "originalKeys": [
    "detection/2026-05-20/上海/张三/设备A/20260520_161000_det_xxx/originals/车间A/img001.jpg"
  ],
  "captureInfo": {
    "captureDate": "2026-05-20",
    "region": "上海",
    "collector": "张三",
    "deviceName": "设备A"
  },
  "modelId": 7,
  "threshold": 0.5
}
```

### 5.2 detection.task.finished

```json
{
  "eventId": "evt_002",
  "eventType": "DETECTION_TASK_FINISHED",
  "eventTime": "2026-05-20T16:18:32+08:00",
  "taskId": "det_20260520_xxx",
  "status": "COMPLETED",
  "resultOssPrefix": "detection/2026-05-20/上海/张三/设备A/20260520_161000_det_xxx/results/",
  "resultJsonKey": "detection/2026-05-20/上海/张三/设备A/20260520_161000_det_xxx/results/detection_results.json",
  "previewKeys": [
    "detection/.../results/preview/img001.jpg"
  ],
  "statistics": {
    "bsgxx_count": 10,
    "bsgzx_count": 2,
    "bsggh_count": 1
  },
  "successfulImages": 13,
  "failedImages": 0,
  "errorMessage": null,
  "finishedAt": "2026-05-20T16:18:31+08:00"
}
```

### 5.3 Optional detection.task.progress

```json
{
  "eventId": "evt_003",
  "eventType": "DETECTION_TASK_PROGRESS",
  "eventTime": "2026-05-20T16:12:10+08:00",
  "taskId": "det_20260520_xxx",
  "stage": "DETECTING",
  "processedImages": 25,
  "totalImages": 100,
  "message": "FastAPI 正在检测第 2 批图片"
}
```

---

## 6. Task State Machine

推荐固定以下任务状态：

- `CREATED`
- `UPLOADING`
- `UPLOADED`
- `QUEUED`
- `DETECTING`
- `UPLOADING_RESULT`
- `COMPLETED`
- `PARTIAL_FAILED`
- `FAILED`
- `TIMEOUT`

### 6.1 Main Flow

```text
CREATED -> UPLOADING -> UPLOADED -> QUEUED -> DETECTING -> UPLOADING_RESULT -> COMPLETED
```

### 6.2 Failure Flow

```text
UPLOADED -> QUEUED -> FAILED
DETECTING -> FAILED
UPLOADING_RESULT -> PARTIAL_FAILED
DETECTING -> TIMEOUT
```

### 6.3 Status Meaning

- `CREATED`
  - Java 已创建任务，还没开始上传
- `UPLOADING`
  - 前端正在通过预签名 URL 上传 OSS
- `UPLOADED`
  - 所有原图已确认上传
- `QUEUED`
  - Java 已发 Kafka，等待 FastAPI 消费
- `DETECTING`
  - FastAPI 已开始执行检测
- `UPLOADING_RESULT`
  - FastAPI 正在写回结果到 OSS
- `COMPLETED`
  - 结果已归档，Java 已确认落库
- `PARTIAL_FAILED`
  - 任务整体完成，但部分结果失败
- `FAILED`
  - 任务整体失败
- `TIMEOUT`
  - 超时未完成

---

## 7. End-to-End Flow

### 7.1 Upload and Dispatch

1. 前端选择固定结构文件夹
2. 前端自动解析 `captureDate / region / collector / deviceName`
3. 用户在页面确认或修改解析值
4. 前端请求 Java 创建任务
5. Java 写入 MySQL：`CREATED`
6. Java 返回 OSS 预签名上传地址
7. 前端上传图片到 OSS
8. 前端通知 Java “上传完成”
9. Java 更新 MySQL：`UPLOADED`
10. Java 发送 `detection.task.created`
11. Java 更新 MySQL：`QUEUED`

### 7.2 Detect and Archive

1. FastAPI 消费 `detection.task.created`
2. FastAPI 开始检测
3. Java 可在任务进入检测阶段时写 `started_at`
4. FastAPI 检测完成后将标注图和结果 JSON 写入 OSS
5. FastAPI 发送 `detection.task.finished`
6. Java 消费完成消息
7. Java 更新：
   - `status`
   - `result_oss_prefix`
   - `result_json_oss_key`
   - `statistics`
   - `finished_at`
8. 前端通过 Java 接口查询最终状态和结果

---

## 8. Result JSON Design

`detection_results.json` 作为任务明细结果文件，建议至少包含：

- `taskId`
- `captureInfo`
- `processedAt`
- `statistics`
- `results`

每张图片结果建议包含：

- `fileName`
- `objectKey`
- `annotatedKey`
- `captureDate`
- `region`
- `collector`
- `deviceName`
- `detections`
- `status`

示例：

```json
{
  "taskId": "det_20260520_xxx",
  "captureInfo": {
    "captureDate": "2026-05-20",
    "region": "上海",
    "collector": "张三",
    "deviceName": "设备A"
  },
  "processedAt": "2026-05-20T16:18:31+08:00",
  "statistics": {
    "bsgxx_count": 10,
    "bsgzx_count": 2,
    "bsggh_count": 1
  },
  "results": [
    {
      "fileName": "img001.jpg",
      "objectKey": "detection/.../originals/车间A/img001.jpg",
      "annotatedKey": "detection/.../results/preview/img001.jpg",
      "captureDate": "2026-05-20",
      "region": "上海",
      "collector": "张三",
      "deviceName": "设备A",
      "status": "success",
      "detections": []
    }
  ]
}
```

---

## 9. Middleware Roles

### 9.1 MySQL

- 保存任务主记录
- 保存业务检索字段
- 保存结果 JSON 路径
- 保存检测开始/结束时间

### 9.2 OSS

- 保存原图
- 保存结果图
- 保存结果 JSON

### 9.3 Kafka

- 解耦 Java 和 FastAPI
- 支撑异步任务分发
- 支撑后续扩容和重试

### 9.4 Optional Future Middleware

- `Redis`
  - 实时进度缓存
  - 防重复提交
  - 幂等锁
- `Quartz` 或 `XXL-JOB`
  - 任务超时扫描
  - 失败补偿

---

## 10. Phased Implementation Tasks

### Phase 1: Main Task Flow

目标：先把“上传完成 -> Kafka 发任务 -> FastAPI 检测 -> 结果回写 -> Java 更新状态”打通。

1. Spring Boot 补齐任务主表字段
2. Spring Boot 固定状态机
3. Spring Boot 生成新 OSS 路径规则
4. 前端解析并确认采集目录信息
5. Spring Boot 集成 Kafka Producer
6. FastAPI 增加 Kafka Consumer
7. FastAPI 将结果写入 OSS
8. FastAPI 发送 `detection.task.finished`
9. Spring Boot 集成 Kafka Consumer
10. 前端继续通过 Java 轮询任务状态

### Phase 2: Reliability

目标：避免重复消费、重复检测和状态错乱。

1. Java 消费结果消息做幂等
2. FastAPI 消费创建消息做幂等
3. 完善失败状态与错误信息落库
4. 增加超时任务处理
5. 增加重试策略

### Phase 3: Observability

目标：便于排障和性能观察。

1. 全链路日志统一带 `taskId`
2. 增加可选进度事件 `detection.task.progress`
3. 统计排队耗时、检测耗时、总耗时
4. 前端展示更细粒度任务进度

### Phase 4: Future Enhancements

1. 增加 `detection_task_item`
2. 引入 Redis 做实时状态缓存
3. 引入调度器做补偿和巡检
4. 增加告警与监控

---

## 11. Execution Order Recommendation

建议后续实施顺序：

1. 先完成 Phase 1
2. 再补 Phase 2 的幂等和失败处理
3. 最后再做 Phase 3 和 Phase 4

这样能确保每个阶段都能独立验证，避免一次性大改导致链路不稳。

---

## 12. Acceptance Criteria

当以下条件都满足时，认为 Kafka 版任务架构达到可用状态：

1. 前端可解析并确认业务目录字段
2. Java 可生成带业务信息的 OSS 路径
3. 原图可直传到 OSS
4. 上传完成后 Java 能发 Kafka 任务消息
5. FastAPI 能消费任务并执行检测
6. FastAPI 能将结果 JSON 和标注图写回 OSS
7. FastAPI 能发 Kafka 完成消息
8. Java 能消费完成消息并更新 MySQL
9. `result_json_oss_key` 能作为结果入口被前端访问
10. `finished_at` 以 FastAPI 完成并返回结果事件后为准
