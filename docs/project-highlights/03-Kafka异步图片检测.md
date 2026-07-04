# Kafka 异步图片检测任务代码实现详解

## 一、亮点简历写法

设计基于 Kafka、OSS 和 Python ONNX Worker 的异步图片检测架构，将图片上传、任务调度、模型推理和结果回传解耦；采用 `dispatchId` 拒绝旧派发结果、`eventId` 消费幂等、手动提交 Offset 和部分失败状态，支持单图与批量任务可靠执行及 Worker 水平扩展。

## 二、整体架构：事件驱动 + 对象存储

```text
Vue 前端
  │ 1. 创建任务/获取预签名 URL
  ├──────────────────────→ Spring Boot / MySQL
  │ 2. PUT 原图
  └──────────────────────→ OSS originals/
                              │
Spring Boot ── task.created ──┼─→ Kafka
                                      │
                                      ▼
                              Python ONNX Worker
                                      │ 下载原图/推理
                                      ▼
                              OSS Result/ + JSON
                                      │
Spring Boot ← task.finished ← Kafka ←─┘
  │ 校验 dispatchId/eventId，更新任务
  ▼
Vue 轮询进度并读取签名结果地址
```

## 三、代码位置索引

| 模块 | 文件 | 作用 |
| --- | --- | --- |
| 单图前端流程 | `frontend/src/services/singleImageKafkaDetection.js` | 创建、上传、确认、轮询 |
| 任务服务 | `DetectionTaskServiceImpl.java` | 状态机、幂等、结果落库 |
| 异步派发 | `DetectionTaskDispatchServiceImpl.java` | 构造并发送 Created 事件 |
| 生产者 | `DetectionTaskEventPublisher.java` | 等待 Kafka Broker ACK |
| 完成监听 | `DetectionTaskFinishedEventListener.java` | 消费 Finished 事件 |
| Worker | `kafka_detection_worker.py` | 下载、推理、上传、回传 |
| 推理引擎 | `detection_engine.py` | ONNX 图片推理 |
| 结果上传 | `oss_result_uploader.py` | 标注图与 JSON 上传 |
| 事件模型 | `kafka_event_models.py` | Python 事件解析和序列化 |

## 四、详细流程图

### 4.1 正常检测流程

```text
Step 1  POST /api/detection/tasks
        taskId=det_20260704_001，status=UPLOADING
   ↓
Step 2  返回 originals/ 前缀及每张图片的 OSS PUT URL
   ↓
Step 3  浏览器直传 OSS，避免图片经过 Spring Boot
   ↓
Step 4  POST /tasks/{taskId}/uploaded
        数据库条件抢占 UPLOADING/FAILED → UPLOADED
        生成 dispatchId=dispatch-B
   ↓
Step 5  发布 detection.task.created，Key=taskId
   ↓
Step 6  Worker 下载图片，执行 ONNX，生成 detections/statistics
   ↓
Step 7  上传标注图和 detection_results.json
   ↓
Step 8  发布 detection.task.finished
   ↓
Step 9  发布成功后才 commit Created 消息 Offset
   ↓
Step 10 Java Listener 校验派发和事件，更新 COMPLETED
```

### 4.2 重试与旧事件流程

```text
第一次派发 dispatch-A ── Worker A 处理缓慢
用户重试生成 dispatch-B ── Worker B 先完成
                         ↓
数据库当前 dispatchId=dispatch-B，写入 B 结果
                         ↓
Worker A 的 Finished 晚到
                         ↓
event.dispatchId != task.dispatchId → 忽略旧事件
```

### 4.3 部分失败流程

```text
100 张图片
  ├─ 98 张推理成功 → 标注图和检测记录
  └─ 2 张异常 → failures 列表
         ↓
successfulImages=98, failedImages=2
status=PARTIAL_FAILED
         ↓
保留 98 张成功结果，同时向用户展示失败信息
```

## 五、事件和状态结构

### 5.1 Created 事件

```json
{
  "eventType": "DETECTION_TASK_CREATED",
  "taskId": "det_20260704_001",
  "dispatchId": "dispatch-B",
  "bucketName": "doorhandle",
  "sourcePrefix": "tasks/001/originals/",
  "originalKeys": ["tasks/001/originals/door.jpg"],
  "modelId": 8,
  "threshold": 0.5
}
```

### 5.2 状态机

| 状态 | 含义 | 下一状态 |
| --- | --- | --- |
| UPLOADING | 等待 OSS 上传 | UPLOADED/FAILED |
| UPLOADED | 上传确认并等待派发 | QUEUED/FAILED |
| PROCESSING | Worker 正在处理 | UPLOADING_RESULT/FAILED |
| COMPLETED | 全部成功 | 进入质检 |
| PARTIAL_FAILED | 部分图片失败 | 保留结果并质检 |
| FAILED | 任务失败 | RETRY/重新上传 |

## 六、代码详解

### 6.1 OSS 预签名直传

文件：`DetectionTaskServiceImpl.createTask()`

```java
String objectKey = uploadPrefix + buildObjectName(file);
URL putUrl = ossStorageService.generatePutUrl(
        objectKey, file.getContentType(),
        Duration.ofMinutes(ossProperties.getUploadUrlExpireMinutes()));
uploadUrls.add(DetectionUploadUrlItem.builder()
        .fileName(file.getFileName())
        .objectKey(objectKey)
        .putUrl(putUrl.toString()).build());
```

逐句解释：

1. 服务端统一生成对象 Key，客户端不能任意覆盖其他目录。
2. PUT URL 有过期时间和 Content-Type 约束。
3. 图片直接进入 OSS，Web 服务只保存元数据。
4. 返回 `objectKey` 供上传确认时进行白名单校验。

### 6.2 上传确认的原子抢占

```java
String dispatchId = UUID.randomUUID().toString();
claim.setDispatchId(dispatchId);
if (detectionTaskMapper.claimUploaded(claim) != 1) {
    return buildProgressResponse(getTask(taskId), "任务已进入检测阶段");
}
detectionTaskDispatchService.dispatchTaskAsync(taskId);
```

逐句解释：

1. 每次有效派发生成新的 `dispatchId`。
2. Mapper 只允许指定前置状态完成迁移，两个 `/uploaded` 请求只有一个成功。
3. 未抢占到的请求直接返回当前进度，不重复发送 Kafka 消息。
4. 数据库状态先落地，再由异步派发器发送事件。

### 6.3 生产者等待 Broker ACK

文件：`DetectionTaskEventPublisher.publishCreated()`

```java
kafkaTemplate.send(topic, event.getTaskId(), event)
        .get(kafkaTaskProperties.getSendTimeoutMs(), TimeUnit.MILLISECONDS);
```

逐句解释：

1. `taskId` 作为 Key，使同一任务事件稳定进入同一分区。
2. `get` 等待 Broker 确认，不能把“放入客户端缓冲”当作发送成功。
3. 超时、执行异常和线程中断分别转换为业务失败。

### 6.4 Worker 处理与手动 Offset

文件：`kafka_detection_worker.py`

```python
consumer = Consumer({
    "bootstrap.servers": settings.kafka_bootstrap_servers,
    "group.id": settings.consumer_group,
    "enable.auto.commit": False,
})
finished_payload = serialize_finished_event(
    process_created_event(task, bucket, default_detector)
)
publish_finished_and_commit(
    producer, consumer, message, settings.task_finished_topic,
    message.key(), finished_payload.encode("utf-8"), timeout
)
```

逐句解释：

1. 关闭自动提交，避免任务刚拉取就被视为完成。
2. `process_created_event` 负责逐图下载、推理、统计和上传。
3. `publish_finished_and_commit` 等 Finished 事件确认送达后才提交源 Offset。
4. 若结果事件失败，源消息仍可再次投递，形成至少一次语义。

### 6.5 Java 侧幂等

```java
boolean staleDispatch = StringUtils.hasText(task.getDispatchId())
        && !Objects.equals(task.getDispatchId(), event.getDispatchId());
boolean duplicateEvent = StringUtils.hasText(event.getEventId())
        && Objects.equals(task.getLastFinishedEventId(), event.getEventId());
if (staleDispatch || duplicateEvent) return;
```

逐句解释：

1. `staleDispatch` 阻止旧的一次派发覆盖重试结果。
2. `duplicateEvent` 阻止 Kafka 重复投递同一完成事件。
3. 两种情况都安全忽略并记录日志，不抛异常触发无意义重试。

## 七、关键设计总结

| 特性 | 实现方式 | 代码位置 |
| --- | --- | --- |
| 大文件卸载 | OSS 预签名直传 | `createTask` |
| 削峰解耦 | Created/Finished 双 Topic | Kafka 配置 |
| 发送可靠性 | 等待 Broker ACK | `DetectionTaskEventPublisher` |
| 至少一次处理 | 关闭自动提交 | `kafka_detection_worker.py` |
| 旧结果隔离 | `dispatchId` | `applyFinishedEvent` |
| 重复事件幂等 | `eventId` | `lastFinishedEventId` |
| 批量容错 | `PARTIAL_FAILED` | Worker 统计逻辑 |

## 八、面试问题与答案

### 1. 为什么不用同步 HTTP 调 Python？

推理耗时长且有峰值。Kafka 能解耦服务、保存积压任务并让多个 Worker 共享消费。

### 2. Kafka 是否保证只消费一次？

本项目采用至少一次语义，所以必须使用 `eventId`、`dispatchId` 和数据库状态实现业务幂等。

### 3. 为什么结果发布成功后才提交 Offset？

若先提交，Finished 发布失败后 Created 不会再投递，任务会永久停在处理中。

### 4. 为什么要同时有 dispatchId 和 eventId？

`eventId` 识别重复消息；`dispatchId` 识别同一任务不同重试批次的旧结果，解决的问题不同。

### 5. 如何扩展吞吐量？

增加 Topic 分区和同 Consumer Group 的 Worker 数量，单任务事件使用 taskId Key 保持分区内顺序。

### 6. 当前还能如何增强？

可增加失败 Topic/DLQ、Worker 幂等结果缓存、消费延迟监控和基于 GPU 使用率的弹性扩容。
