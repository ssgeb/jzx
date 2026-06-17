# Kafka Detection Task Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current synchronous Java-to-FastAPI detection dispatch with an asynchronous Kafka-based task pipeline while preserving OSS direct upload, MySQL task tracking, capture metadata, and result JSON indexing.

**Architecture:** Spring Boot remains the entrypoint for task creation, OSS upload signing, and task/result queries. After upload confirmation, Spring Boot publishes a `detection.task.created` event to Kafka instead of calling FastAPI synchronously. A new Python Kafka worker consumes the event, runs detection, writes results to OSS, publishes a `detection.task.finished` event, and Spring Boot consumes that event to finalize task state in MySQL.

**Tech Stack:** Spring Boot 3.2, MyBatis-Plus, MySQL, Aliyun OSS SDK, Spring for Apache Kafka, Python 3, FastAPI, confluent-kafka, existing detection model pipeline.

---

## File Structure

### Java backend

- Modify: `pom.xml`
  - Add Kafka dependency and test support if needed.
- Modify: `src/main/resources/application.yml`
  - Add Kafka bootstrap servers, topic names, consumer group, and toggles.
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/config/properties/KafkaTaskProperties.java`
  - Bind Kafka task configuration.
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/config/KafkaConfig.java`
  - Configure producer/consumer factories, JSON serializers, listener container factory.
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskCreatedEvent.java`
  - Kafka payload for created tasks.
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskFinishedEvent.java`
  - Kafka payload for completed tasks.
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskEventCaptureInfo.java`
  - Shared nested capture-info payload for events.
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskEventPublisher.java`
  - Publish created events to Kafka.
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskFinishedEventListener.java`
  - Consume finished events and update tasks.
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskDispatchServiceImpl.java`
  - Stop synchronous `RestTemplate` dispatch and publish Kafka events instead.
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImpl.java`
  - Add `QUEUED` transition, event-building helpers, and finished-event application logic.
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/RemoteDetectionTaskRequest.java`
  - Likely deprecate/remove from dispatch flow after Kafka switch.
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/RemoteDetectionTaskResponse.java`
  - Likely deprecate/remove from dispatch flow after Kafka switch.
- Create: `src/test/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskEventPublisherTest.java`
  - Publisher unit tests.
- Create: `src/test/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskFinishedEventListenerTest.java`
  - Consumer result-application tests.
- Modify: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImplTest.java`
  - Update for `QUEUED` state transition and event generation.

### Python detection side

- Create: `kafka_detection_worker.py`
  - Dedicated Kafka consumer/producer process for detection tasks.
- Create: `kafka_settings.py`
  - Read Kafka/OSS/environment config for the worker.
- Create: `kafka_event_models.py`
  - Parse and validate created/finished event payloads.
- Create: `oss_result_uploader.py`
  - Upload annotated images and `detection_results.json` to OSS.
- Modify: `main.py`
  - Extract reusable detection functions needed by the worker without requiring HTTP endpoints.
- Create: `requirements-kafka.txt` or modify existing Python dependency install instructions
  - Add `confluent-kafka` and any missing OSS package on Python side if not already installed.

### Frontend

- Modify: `frontend/src/views/ImageDetection.vue`
  - Align state text to `QUEUED`/Kafka pipeline and keep polling through Java.

### Database / docs

- Modify: `src/main/resources/db/schema.sql`
  - Add any missing Kafka/idempotency columns only if needed in Phase 1.
- Create: `docs/2026-05-20-kafka-detection-task-migration.sql`
  - Migration for existing MySQL deployments.
- Modify: `docs/2026-05-20-kafka-detection-task-plan.md`
  - Optionally link to this implementation plan once it exists.

---

### Task 1: Add Kafka Configuration and Event Models in Spring Boot

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/config/properties/KafkaTaskProperties.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/config/KafkaConfig.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskEventCaptureInfo.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskCreatedEvent.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskFinishedEvent.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskEventPublisherTest.java`

- [ ] **Step 1: Write the failing configuration/property binding test**

Create `src/test/java/com/ruanzhu/doorhandlecatch/config/properties/KafkaTaskPropertiesTest.java` with a focused Spring binding test that proves these keys bind correctly:

```java
@SpringBootTest(classes = KafkaTaskProperties.class)
@EnableConfigurationProperties(KafkaTaskProperties.class)
@TestPropertySource(properties = {
        "app.kafka.enabled=true",
        "app.kafka.bootstrap-servers=localhost:9092",
        "app.kafka.topics.task-created=detection.task.created",
        "app.kafka.topics.task-finished=detection.task.finished",
        "app.kafka.consumer-group=detection-java"
})
class KafkaTaskPropertiesTest {

    @Autowired
    private KafkaTaskProperties properties;

    @Test
    void bindsKafkaTaskProperties() {
        assertTrue(properties.isEnabled());
        assertEquals("localhost:9092", properties.getBootstrapServers());
        assertEquals("detection.task.created", properties.getTopics().getTaskCreated());
        assertEquals("detection.task.finished", properties.getTopics().getTaskFinished());
        assertEquals("detection-java", properties.getConsumerGroup());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./mvnw -Dtest=KafkaTaskPropertiesTest test
```

Expected:
- FAIL because `KafkaTaskProperties` and related binding classes do not exist yet.

- [ ] **Step 3: Add Kafka dependency and configuration binding implementation**

Update `pom.xml` to add:

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

Create `KafkaTaskProperties.java` with this shape:

```java
@Data
@Configuration
@ConfigurationProperties(prefix = "app.kafka")
public class KafkaTaskProperties {
    private boolean enabled = false;
    private String bootstrapServers;
    private String consumerGroup = "doorhandlecatch-detection";
    private Topics topics = new Topics();

    @Data
    public static class Topics {
        private String taskCreated = "detection.task.created";
        private String taskFinished = "detection.task.finished";
        private String taskProgress = "detection.task.progress";
    }
}
```

Create event DTOs with explicit fields:

```java
@Data
@Builder
public class DetectionTaskCreatedEvent {
    private String eventId;
    private String eventType;
    private String eventTime;
    private String taskId;
    private String bucketName;
    private String sourcePrefix;
    private List<String> originalKeys;
    private DetectionTaskEventCaptureInfo captureInfo;
    private Integer modelId;
    private BigDecimal threshold;
}
```

```java
@Data
@Builder
public class DetectionTaskFinishedEvent {
    private String eventId;
    private String eventType;
    private String eventTime;
    private String taskId;
    private String status;
    private String resultOssPrefix;
    private String resultJsonKey;
    private List<String> previewKeys;
    private Map<String, Object> statistics;
    private Integer totalImages;
    private Integer successfulImages;
    private Integer failedImages;
    private String errorMessage;
    private String finishedAt;
}
```

Update `application.yml` with placeholders:

```yml
app:
  kafka:
    enabled: ${APP_KAFKA_ENABLED:false}
    bootstrap-servers: ${APP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer-group: ${APP_KAFKA_CONSUMER_GROUP:doorhandlecatch-detection}
    topics:
      task-created: ${APP_KAFKA_TOPIC_TASK_CREATED:detection.task.created}
      task-finished: ${APP_KAFKA_TOPIC_TASK_FINISHED:detection.task.finished}
      task-progress: ${APP_KAFKA_TOPIC_TASK_PROGRESS:detection.task.progress}
```

Create `KafkaConfig.java` to register:
- `ProducerFactory<String, DetectionTaskCreatedEvent>`
- `KafkaTemplate<String, DetectionTaskCreatedEvent>`
- `ConsumerFactory<String, DetectionTaskFinishedEvent>`
- `ConcurrentKafkaListenerContainerFactory<String, DetectionTaskFinishedEvent>`

Use Spring JSON serializer/deserializer with trusted package limited to the app namespace.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
./mvnw -Dtest=KafkaTaskPropertiesTest test
```

Expected:
- PASS with `KafkaTaskProperties` bound successfully.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/main/java/com/ruanzhu/doorhandlecatch/config src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event src/test/java/com/ruanzhu/doorhandlecatch/config/properties/KafkaTaskPropertiesTest.java
git commit -m "feat: add kafka config and event models"
```

### Task 2: Publish Detection Task Events Instead of Synchronous FastAPI Calls

**Files:**
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskEventPublisher.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskDispatchServiceImpl.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImpl.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskEventPublisherTest.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImplTest.java`

- [ ] **Step 1: Write the failing publisher test**

Create `DetectionTaskEventPublisherTest.java`:

```java
@ExtendWith(MockitoExtension.class)
class DetectionTaskEventPublisherTest {

    @Mock
    private KafkaTemplate<String, DetectionTaskCreatedEvent> kafkaTemplate;

    @Mock
    private KafkaTaskProperties properties;

    @InjectMocks
    private DetectionTaskEventPublisher publisher;

    @Test
    void publishesCreatedEventUsingTaskIdAsKey() {
        KafkaTaskProperties.Topics topics = new KafkaTaskProperties.Topics();
        topics.setTaskCreated("detection.task.created");
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getTopics()).thenReturn(topics);

        DetectionTaskCreatedEvent event = DetectionTaskCreatedEvent.builder()
                .taskId("det_123")
                .build();

        publisher.publishCreated(event);

        verify(kafkaTemplate).send("detection.task.created", "det_123", event);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./mvnw -Dtest=DetectionTaskEventPublisherTest test
```

Expected:
- FAIL because the publisher class does not exist yet.

- [ ] **Step 3: Implement publisher and replace synchronous dispatch**

Create `DetectionTaskEventPublisher.java`:

```java
@Service
@RequiredArgsConstructor
public class DetectionTaskEventPublisher {
    private final KafkaTemplate<String, DetectionTaskCreatedEvent> kafkaTemplate;
    private final KafkaTaskProperties kafkaTaskProperties;

    public void publishCreated(DetectionTaskCreatedEvent event) {
        if (!kafkaTaskProperties.isEnabled()) {
            throw new BusinessException("Kafka 未启用，无法发送检测任务");
        }
        kafkaTemplate.send(
                kafkaTaskProperties.getTopics().getTaskCreated(),
                event.getTaskId(),
                event
        );
    }
}
```

Refactor `DetectionTaskDispatchServiceImpl`:
- Remove `RemoteFastApiProperties` and `RestTemplate` from the dispatch path.
- Inject `DetectionTaskEventPublisher`.
- Build `DetectionTaskCreatedEvent` from the task.
- Set task status to `QUEUED` instead of `DETECTING`.
- Keep `started_at` unset here; it will be set when Java receives a progress/finished marker or when the worker explicitly announces detection start in a later phase.

Add a helper in `DetectionTaskServiceImpl`:

```java
public DetectionTaskCreatedEvent buildCreatedEvent(DetectionTask task) {
    return DetectionTaskCreatedEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("DETECTION_TASK_CREATED")
            .eventTime(OffsetDateTime.now(ZoneOffset.ofHours(8)).toString())
            .taskId(task.getTaskId())
            .bucketName(getBucketName())
            .sourcePrefix(task.getSourceOssPrefix())
            .originalKeys(readJsonList(task.getOriginalImageKeysJson()))
            .captureInfo(toEventCaptureInfo(task))
            .modelId(task.getModelId())
            .threshold(task.getThreshold())
            .build();
}
```

Update `DetectionTaskServiceImplTest` so upload confirmation now results in a queued task handoff instead of synchronous detection.

- [ ] **Step 4: Run targeted tests to verify they pass**

Run:

```bash
./mvnw -Dtest=DetectionTaskEventPublisherTest,DetectionTaskServiceImplTest test
```

Expected:
- PASS
- No `RestTemplate` path is exercised during dispatch.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ruanzhu/doorhandlecatch/service/detection src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskDispatchServiceImpl.java src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImpl.java src/test/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskEventPublisherTest.java src/test/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImplTest.java
git commit -m "feat: publish detection tasks to kafka"
```

### Task 3: Consume Finished Events and Finalize Tasks in Spring Boot

**Files:**
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskFinishedEventListener.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImpl.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskFinishedEventListenerTest.java`

- [ ] **Step 1: Write the failing listener test**

Create `DetectionTaskFinishedEventListenerTest.java`:

```java
@ExtendWith(MockitoExtension.class)
class DetectionTaskFinishedEventListenerTest {

    @Mock
    private DetectionTaskServiceImpl detectionTaskService;

    @InjectMocks
    private DetectionTaskFinishedEventListener listener;

    @Test
    void appliesFinishedEventToTaskService() {
        DetectionTaskFinishedEvent event = DetectionTaskFinishedEvent.builder()
                .taskId("det_123")
                .status("COMPLETED")
                .resultJsonKey("detection/.../results/detection_results.json")
                .build();

        listener.onFinished(event);

        verify(detectionTaskService).applyFinishedEvent(event);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./mvnw -Dtest=DetectionTaskFinishedEventListenerTest test
```

Expected:
- FAIL because the listener and service hook do not exist yet.

- [ ] **Step 3: Implement finished-event consumer**

Create `DetectionTaskFinishedEventListener.java`:

```java
@Service
@RequiredArgsConstructor
public class DetectionTaskFinishedEventListener {

    private final DetectionTaskServiceImpl detectionTaskService;

    @KafkaListener(
            topics = "#{@kafkaTaskProperties.topics.taskFinished}",
            groupId = "#{@kafkaTaskProperties.consumerGroup}",
            containerFactory = "detectionTaskFinishedKafkaListenerContainerFactory"
    )
    public void onFinished(DetectionTaskFinishedEvent event) {
        detectionTaskService.applyFinishedEvent(event);
    }
}
```

Add `applyFinishedEvent` to `DetectionTaskServiceImpl`:
- Load task by `taskId`
- Set `started_at` if event carries it in a future extension or leave existing value
- Move task to `UPLOADING_RESULT`, then to final state
- Persist:
  - `result_json_oss_key`
  - `result_oss_prefix`
  - `preview_image_keys_json`
  - `statistics_json`
  - `successful_images`
  - `failed_images`
  - `processed_images`
  - `finished_at`
  - `error_message`

Use the event’s `finishedAt` when present. Parse it with `OffsetDateTime.parse(...).toLocalDateTime()` rather than always using `LocalDateTime.now()`.

- [ ] **Step 4: Run targeted tests to verify they pass**

Run:

```bash
./mvnw -Dtest=DetectionTaskFinishedEventListenerTest,DetectionTaskServiceImplTest test
```

Expected:
- PASS
- `finished_at` comes from the event payload when available.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskFinishedEventListener.java src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImpl.java src/test/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskFinishedEventListenerTest.java src/test/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImplTest.java
git commit -m "feat: consume finished detection events from kafka"
```

### Task 4: Build the Python Kafka Worker and OSS Result Writer

**Files:**
- Create: `kafka_settings.py`
- Create: `kafka_event_models.py`
- Create: `oss_result_uploader.py`
- Create: `kafka_detection_worker.py`
- Modify: `main.py`

- [ ] **Step 1: Write the failing worker unit test**

Create `tests_python/test_kafka_event_models.py` (or `python_tests/test_kafka_event_models.py` if no test package exists) with:

```python
from kafka_event_models import DetectionTaskCreatedEvent


def test_created_event_parses_required_fields():
    payload = {
        "eventId": "evt_001",
        "eventType": "DETECTION_TASK_CREATED",
        "eventTime": "2026-05-20T16:10:00+08:00",
        "taskId": "det_123",
        "bucketName": "bucket",
        "sourcePrefix": "detection/.../originals/",
        "originalKeys": ["detection/.../originals/img001.jpg"],
        "captureInfo": {
            "captureDate": "2026-05-20",
            "region": "上海",
            "collector": "张三",
            "deviceName": "设备A"
        },
        "modelId": 7,
        "threshold": 0.5
    }

    event = DetectionTaskCreatedEvent.from_dict(payload)

    assert event.task_id == "det_123"
    assert event.capture_info.capture_date == "2026-05-20"
    assert event.original_keys[0].endswith("img001.jpg")
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
pytest tests_python/test_kafka_event_models.py -v
```

Expected:
- FAIL because the event model module does not exist yet.

- [ ] **Step 3: Implement Python worker modules**

Create `kafka_settings.py` to read:

```python
KAFKA_BOOTSTRAP_SERVERS
KAFKA_TASK_CREATED_TOPIC
KAFKA_TASK_FINISHED_TOPIC
KAFKA_CONSUMER_GROUP
ALIYUN_OSS_ENDPOINT
ALIYUN_OSS_BUCKET
ALIYUN_OSS_ACCESS_KEY_ID
ALIYUN_OSS_ACCESS_KEY_SECRET
```

Create `kafka_event_models.py` with dataclasses:

```python
@dataclass
class CaptureInfo:
    capture_date: str
    region: str
    collector: str
    device_name: str

@dataclass
class DetectionTaskCreatedEvent:
    event_id: str
    event_type: str
    event_time: str
    task_id: str
    bucket_name: str
    source_prefix: str
    original_keys: list[str]
    capture_info: CaptureInfo
    model_id: int | None
    threshold: float
```

Create `oss_result_uploader.py` to:
- Read original image bytes from OSS
- Upload annotated images to `results/preview/`
- Upload `detection_results.json` to `results/`
- Return `result_oss_prefix`, `result_json_key`, `preview_keys`

Create `kafka_detection_worker.py` to:
- Use `confluent_kafka.Consumer`
- Subscribe to `detection.task.created`
- For each message:
  - Parse payload
  - Download originals from OSS
  - Reuse model inference helpers from `main.py`
  - Run detection
  - Upload results to OSS
  - Produce a `detection.task.finished` message
- Commit Kafka offsets only after a successful produce of the finished event

Modify `main.py` only to extract reusable functions that the worker can import, for example:
- model loading
- single-image inference from PIL / bytes
- annotation rendering

Do not remove the existing HTTP APIs in this task; keep them working while adding the worker.

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
pytest tests_python/test_kafka_event_models.py -v
python -m py_compile kafka_settings.py kafka_event_models.py oss_result_uploader.py kafka_detection_worker.py
```

Expected:
- Event model test passes
- Python compile step exits successfully

- [ ] **Step 5: Commit**

```bash
git add kafka_settings.py kafka_event_models.py oss_result_uploader.py kafka_detection_worker.py main.py tests_python/test_kafka_event_models.py
git commit -m "feat: add kafka detection worker"
```

### Task 5: Align Frontend State Flow and Delivery Docs

**Files:**
- Modify: `frontend/src/views/ImageDetection.vue`
- Create: `docs/2026-05-20-kafka-detection-task-migration.sql`
- Modify: `docs/2026-05-20-kafka-detection-task-plan.md`

- [ ] **Step 1: Write the failing UI acceptance checklist**

Create a short markdown QA checklist in `docs/qa-kafka-detection-task-checklist.md`:

```markdown
- [ ] After upload confirmation, UI shows queued state instead of immediate detecting
- [ ] While Kafka/FastAPI processing runs, polling still works through Java APIs
- [ ] Result page shows source prefix, result prefix, result JSON link, detection start time, detection end time
```

This step intentionally defines the acceptance target before UI copy changes.

- [ ] **Step 2: Run verification to establish current mismatch**

Run:

```bash
npm --prefix frontend run build
```

Expected:
- Current build may fail for existing environment reasons such as missing `terser`.
- Regardless of build outcome, current UI text still assumes immediate remote dispatch rather than explicit queueing.

- [ ] **Step 3: Update frontend task messaging and migration doc**

Modify `ImageDetection.vue`:
- Update stage text mapping to include `queued`
- After upload confirmation, show `QUEUED` / “已进入 Kafka 队列，等待检测服务消费”
- Keep polling logic against Java unchanged
- Surface `detectionStartedAt`, `detectionFinishedAt`, `sourceOssPrefix`, and `resultJsonUrl` in the result section if not already shown clearly

Create `docs/2026-05-20-kafka-detection-task-migration.sql` with:
- Existing capture-field migration if not already applied
- Optional comment block showing Kafka-related app config that must be provided at deploy time

Update the architecture doc to link this implementation plan:

```markdown
Implementation plan: `docs/2026-05-20-kafka-detection-task-implementation-plan.md`
```

- [ ] **Step 4: Run verification to confirm UI and docs are in sync**

Run:

```bash
./mvnw test
npm --prefix frontend run build
```

Expected:
- Maven tests pass
- Frontend build either passes or fails only for pre-existing environment reasons such as missing `terser`
- UI source now reflects Kafka queue state and result timing fields

- [ ] **Step 5: Commit**

```bash
git add frontend/src/views/ImageDetection.vue docs/2026-05-20-kafka-detection-task-migration.sql docs/2026-05-20-kafka-detection-task-plan.md docs/2026-05-20-kafka-detection-task-implementation-plan.md docs/qa-kafka-detection-task-checklist.md
git commit -m "docs: finalize kafka detection task rollout plan"
```

## Self-Review

### Spec coverage

This plan covers all Phase 1 items from `docs/2026-05-20-kafka-detection-task-plan.md`:
- Spring Boot Kafka producer
- Kafka consumer on the Python side
- Result write-back to OSS
- Spring Boot Kafka result consumer
- Frontend queue/polling alignment
- Result JSON indexing in MySQL

The intentionally deferred items are:
- `detection.task.progress` topic
- Redis-based progress cache
- timeout retries and scheduler compensation
- `detection_task_item` child table

Those remain in later phases and are not required to ship Phase 1.

### Placeholder scan

No step uses `TBD`, `TODO`, or references an undefined future file without naming it. The main remaining environmental variable is the Python-side dependency install path, which is intentionally called out in Task 4 instead of hidden.

### Type consistency

The plan consistently uses:
- `DetectionTaskCreatedEvent`
- `DetectionTaskFinishedEvent`
- `captureInfo`
- `resultJsonKey`
- `resultOssPrefix`
- Kafka key = `taskId`

The Java and Python payload field names match the architecture document so the worker and backend can serialize/deserialize the same message bodies.

## Execution Handoff

Plan complete and saved to `docs/2026-05-20-kafka-detection-task-implementation-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
