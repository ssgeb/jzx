# Security and Reliability Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce real ADMIN/OPERATOR isolation and make assistant actions plus Kafka detection processing atomic, idempotent, and failure-aware.

**Architecture:** Add one idempotent MySQL migration, map stored roles into Spring Security, and centralize detection-task ownership in a small access policy shared by task and OSS preview services. Use conditional MyBatis updates for assistant actions and upload dispatch, correlate Kafka attempts with `dispatchId`, and let the Python worker commit source offsets only after delivery confirmation.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Security, MyBatis-Plus, MySQL 8, JUnit 5, Mockito, Python 3.11, pytest, confluent-kafka.

---

### Task 1: Persist and map real user roles

**Files:**
- Create: `src/main/resources/db/migration-V14-security-reliability-hardening.sql`
- Modify: `src/main/resources/db/schema.sql`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/entity/User.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/UserDetailsServiceImpl.java`
- Create: `src/test/java/com/ruanzhu/doorhandlecatch/service/UserDetailsServiceImplTest.java`
- Create: `src/test/java/com/ruanzhu/doorhandlecatch/config/SecurityReliabilityMigrationContractTest.java`

- [ ] **Step 1: Write failing role-mapping tests**

```java
@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {
    @Mock UserMapper userMapper;

    @Test
    void mapsStoredAdminRole() {
        User user = new User();
        user.setUsername("admin");
        user.setPassword("hash");
        user.setRole("ADMIN");
        when(userMapper.findByUsername("admin")).thenReturn(user);

        UserDetails details = new UserDetailsServiceImpl(userMapper).loadUserByUsername("admin");

        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void defaultsUnknownRoleToOperator() {
        User user = new User();
        user.setUsername("alice");
        user.setPassword("hash");
        user.setRole("unexpected");
        when(userMapper.findByUsername("alice")).thenReturn(user);

        UserDetails details = new UserDetailsServiceImpl(userMapper).loadUserByUsername("alice");

        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_OPERATOR");
    }
}
```

The migration contract test must assert that V14 contains `users.role`, `dispatch_id`, `last_finished_event_id`, `chat_pending_action.error_message`, an ADMIN backfill for username `admin`, and an OPERATOR fallback.

- [ ] **Step 2: Run the tests and verify RED**

Run: `.\mvnw.cmd -q '-Dtest=UserDetailsServiceImplTest,SecurityReliabilityMigrationContractTest' test`

Expected: compilation or assertion failure because `User.role` and migration V14 do not exist.

- [ ] **Step 3: Add the guarded migration and role mapping**

Add `role` to `User`:

```java
@TableField("role")
private String role;
```

Map the authority without username shortcuts:

```java
String storedRole = user.getRole();
String role = "ADMIN".equalsIgnoreCase(storedRole) ? "ADMIN" : "OPERATOR";
return new org.springframework.security.core.userdetails.User(
        user.getUsername(),
        user.getPassword(),
        List.of(new SimpleGrantedAuthority("ROLE_" + role))
);
```

V14 must use `information_schema.COLUMNS` guards before each `ALTER TABLE`, backfill with:

```sql
UPDATE users
SET role = CASE WHEN LOWER(username) = 'admin' THEN 'ADMIN' ELSE 'OPERATOR' END
WHERE role IS NULL OR role NOT IN ('ADMIN', 'OPERATOR') OR LOWER(username) = 'admin';
```

Add V14 after V13 in `app.database.migration-scripts` and mirror all four columns in `schema.sql`.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run: `.\mvnw.cmd -q '-Dtest=UserDetailsServiceImplTest,SecurityReliabilityMigrationContractTest' test`

Expected: all focused tests pass.

- [ ] **Step 5: Commit this task**

```powershell
git add src/main/resources/db/migration-V14-security-reliability-hardening.sql src/main/resources/db/schema.sql src/main/resources/application.yml src/main/java/com/ruanzhu/doorhandlecatch/entity/User.java src/main/java/com/ruanzhu/doorhandlecatch/service/UserDetailsServiceImpl.java src/test/java/com/ruanzhu/doorhandlecatch/service/UserDetailsServiceImplTest.java src/test/java/com/ruanzhu/doorhandlecatch/config/SecurityReliabilityMigrationContractTest.java
git commit -m "fix: enforce stored user roles"
```

### Task 2: Protect task data and OSS previews with one access policy

**Files:**
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/security/DetectionTaskAccessPolicy.java`
- Create: `src/main/java/com/ruanzhu/doorhandlecatch/service/OssPreviewAuthorizationService.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImpl.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/controller/OssPreviewController.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/config/SecurityConfig.java`
- Create: `src/test/java/com/ruanzhu/doorhandlecatch/security/DetectionTaskAccessPolicyTest.java`
- Create: `src/test/java/com/ruanzhu/doorhandlecatch/service/OssPreviewAuthorizationServiceTest.java`

- [ ] **Step 1: Write failing access-policy tests**

```java
@Test
void operatorCannotAccessForeignTask() {
    DetectionTask task = new DetectionTask();
    task.setCreatedBy("alice");
    Authentication auth = new UsernamePasswordAuthenticationToken(
            "bob", "n/a", List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));

    assertThatThrownBy(() -> policy.assertCanAccess(task, auth))
            .isInstanceOf(BusinessException.class)
            .hasMessage("无权访问该资源");
}

@Test
void adminCanAccessForeignTask() {
    DetectionTask task = new DetectionTask();
    task.setCreatedBy("alice");
    Authentication auth = new UsernamePasswordAuthenticationToken(
            "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    policy.assertCanAccess(task, auth);
}
```

For OSS authorization, mock candidate tasks and prove that an exact referenced key owned by the operator is accepted, while a foreign task, an unreferenced `detection/` key, and a non-`detection/` key all throw the same `无权访问该资源` error.

- [ ] **Step 2: Run the tests and verify RED**

Run: `.\mvnw.cmd -q '-Dtest=DetectionTaskAccessPolicyTest,OssPreviewAuthorizationServiceTest' test`

Expected: compilation failure because both services are absent.

- [ ] **Step 3: Implement the shared policy and preview authorization**

The policy API is:

```java
@Component
public class DetectionTaskAccessPolicy {
    public boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(value -> "ROLE_ADMIN".equals(value.getAuthority()));
    }

    public void assertCanAccess(DetectionTask task, Authentication authentication) {
        boolean owner = authentication != null
                && authentication.getName().equals(task.getCreatedBy());
        if (!isAdmin(authentication) && !owner) {
            throw new BusinessException("无权访问该资源");
        }
    }
}
```

`OssPreviewAuthorizationService.authorize(String key, Authentication authentication)` must reject blank/out-of-prefix keys, load candidate tasks from `DetectionTaskMapper`, parse the three JSON key lists with `ObjectMapper`, compare exact strings, and call `DetectionTaskAccessPolicy.assertCanAccess` on the matching task. It must reject when no exact reference exists.

Replace the username/authority branching in `DetectionTaskServiceImpl` with this policy. Internal finished-event processing must load its task through a separate private `getTaskForSystem` method; request-facing methods continue through ownership checks.

Change security to:

```java
.requestMatchers("/api/oss/preview").authenticated()
```

Call preview authorization before `generateGetUrl` in `OssPreviewController`.

- [ ] **Step 4: Run focused and existing ownership tests**

Run: `.\mvnw.cmd -q '-Dtest=DetectionTaskAccessPolicyTest,OssPreviewAuthorizationServiceTest,DetectionTaskServiceImplTest' test`

Expected: tests pass; ordinary users remain scoped while ADMIN is unscoped.

- [ ] **Step 5: Commit this task**

```powershell
git add src/main/java/com/ruanzhu/doorhandlecatch/security/DetectionTaskAccessPolicy.java src/main/java/com/ruanzhu/doorhandlecatch/service/OssPreviewAuthorizationService.java src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImpl.java src/main/java/com/ruanzhu/doorhandlecatch/controller/OssPreviewController.java src/main/java/com/ruanzhu/doorhandlecatch/config/SecurityConfig.java src/test/java/com/ruanzhu/doorhandlecatch/security/DetectionTaskAccessPolicyTest.java src/test/java/com/ruanzhu/doorhandlecatch/service/OssPreviewAuthorizationServiceTest.java
git commit -m "fix: enforce task and OSS ownership"
```

### Task 3: Make assistant confirmation atomic

**Files:**
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/entity/ChatPendingAction.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/mapper/ChatPendingActionMapper.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/ChatSessionService.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImpl.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImpl.java`
- Modify: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImplTest.java`
- Modify: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImplTest.java`

- [ ] **Step 1: Write failing state-transition tests**

Add service tests that mock mapper row counts:

```java
@Test
void claimsOnlyPendingAction() {
    when(pendingActionMapper.transitionStatus(
            "sess_admin_default", "action-1", "PENDING", "EXECUTING", null))
            .thenReturn(1);

    assertThat(chatSessionService.transitionPendingAction(
            "sess_admin_default", "action-1", "PENDING", "EXECUTING", null))
            .isTrue();
}
```

Add orchestrator tests proving: a zero-row claim never calls `chatGraph.resume`; a successful claim transitions to `COMPLETED`; a thrown `resume` transitions to `FAILED` with a bounded message.

- [ ] **Step 2: Run the tests and verify RED**

Run: `.\mvnw.cmd -q '-Dtest=ChatSessionServiceImplTest,AgentOrchestratorServiceImplTest' test`

Expected: compilation failure because `transitionPendingAction` and mapper CAS do not exist.

- [ ] **Step 3: Implement CAS transitions**

Add `errorMessage` to `ChatPendingAction`. Add this mapper method:

```java
@Update("""
        UPDATE chat_pending_action
        SET status = #{targetStatus},
            confirmed_at = CASE WHEN #{targetStatus} = 'EXECUTING' THEN CURRENT_TIMESTAMP ELSE confirmed_at END,
            error_message = #{errorMessage}
        WHERE session_id = #{sessionId}
          AND action_id = #{actionId}
          AND status = #{expectedStatus}
        """)
int transitionStatus(String sessionId, String actionId, String expectedStatus,
                     String targetStatus, String errorMessage);
```

Expose a boolean `transitionPendingAction(...)` from `ChatSessionService` and return `mapper.transitionStatus(...) == 1`.

In `confirmAction`, replace read-then-write with:

```java
String target = request.isConfirmed() ? "EXECUTING" : "CANCELLED";
if (!chatSessionService.transitionPendingAction(sessionId, actionId, "PENDING", target, null)) {
    throw new BusinessException("待确认动作正在处理或已处理，请勿重复提交");
}
if (!request.isConfirmed()) {
    return appendCancelledMessage(request, payload);
}
try {
    AgentState result = chatGraph.resume(sessionId, Map.of(AgentState.KEY_CONFIRMED, true));
    chatSessionService.transitionPendingAction(sessionId, actionId, "EXECUTING", "COMPLETED", null);
    return appendConfirmedResult(sessionId, result);
} catch (RuntimeException ex) {
    chatSessionService.transitionPendingAction(
            sessionId, actionId, "EXECUTING", "FAILED", abbreviate(ex.getMessage(), 500));
    throw ex;
}
```

Keep response-building logic in private methods so the confirmation method owns only state transitions.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `.\mvnw.cmd -q '-Dtest=ChatSessionServiceImplTest,AgentOrchestratorServiceImplTest' test`

Expected: all confirmation state tests pass.

- [ ] **Step 5: Commit this task**

```powershell
git add src/main/java/com/ruanzhu/doorhandlecatch/entity/ChatPendingAction.java src/main/java/com/ruanzhu/doorhandlecatch/mapper/ChatPendingActionMapper.java src/main/java/com/ruanzhu/doorhandlecatch/service/ChatSessionService.java src/main/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImpl.java src/main/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImpl.java src/test/java/com/ruanzhu/doorhandlecatch/service/impl/ChatSessionServiceImplTest.java src/test/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImplTest.java
git commit -m "fix: make assistant confirmation atomic"
```

### Task 4: Make detection dispatch and completion idempotent

**Files:**
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/entity/DetectionTask.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskCreatedEvent.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskFinishedEvent.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImpl.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/controller/DetectionTaskController.java`
- Modify: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImplTest.java`
- Modify: `src/test/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskEventPublisherTest.java`

- [ ] **Step 1: Write failing idempotency tests**

Add tests proving that a task already in `UPLOADED` or `QUEUED` returns progress without calling `dispatchTaskAsync`, and that only a one-row conditional update dispatches. Add completion tests:

```java
@Test
void ignoresFinishedEventFromStaleDispatch() {
    task.setDispatchId("dispatch-current");
    event.setDispatchId("dispatch-old");

    detectionTaskService.applyFinishedEvent(event);

    verify(detectionTaskMapper, never()).updateById(any());
    verify(chatSessionService, never()).appendAssistantMessage(any(), any(), any(), any(), any());
}
```

Add a duplicate-event test where `lastFinishedEventId` equals the incoming event ID and assert the same no-op behavior.

- [ ] **Step 2: Run the tests and verify RED**

Run: `.\mvnw.cmd -q '-Dtest=DetectionTaskServiceImplTest,DetectionTaskEventPublisherTest' test`

Expected: compilation failure because dispatch correlation fields do not exist.

- [ ] **Step 3: Implement atomic upload claim and event correlation**

Add these mapped entity/event fields:

```java
@TableField("dispatch_id")
private String dispatchId;

@TableField("last_finished_event_id")
private String lastFinishedEventId;
```

Both event DTOs receive `private String dispatchId;`.

Before updating an uploadable task, generate `String dispatchId = UUID.randomUUID().toString()`. Use a `LambdaUpdateWrapper` with the row ID and accepted old statuses (`UPLOADING`, `FAILED`) and set `status`, `stage`, validated keys, model fields, and `dispatch_id`. Dispatch only when `mapper.update(null, wrapper) == 1`; otherwise reload and return current progress.

`markUploaded` constructs a `DetectionTaskUploadedRequest` and delegates to `confirmUploaded`; it must not contain another state transition.

`buildCreatedEvent` copies `task.getDispatchId()`. Mark `applyFinishedEvent` as `@Transactional`, load with `getTaskForSystem`, and return before mutation when:

```java
if (!Objects.equals(task.getDispatchId(), event.getDispatchId())
        || Objects.equals(task.getLastFinishedEventId(), event.getEventId())) {
    return;
}
```

Set `lastFinishedEventId` before the final update. Send the assistant completion notification only after the valid update path.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `.\mvnw.cmd -q '-Dtest=DetectionTaskServiceImplTest,DetectionTaskEventPublisherTest' test`

Expected: dispatch and finished-event idempotency tests pass.

- [ ] **Step 5: Commit this task**

```powershell
git add src/main/java/com/ruanzhu/doorhandlecatch/entity/DetectionTask.java src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskCreatedEvent.java src/main/java/com/ruanzhu/doorhandlecatch/dto/detection/event/DetectionTaskFinishedEvent.java src/main/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImpl.java src/main/java/com/ruanzhu/doorhandlecatch/controller/DetectionTaskController.java src/test/java/com/ruanzhu/doorhandlecatch/service/impl/DetectionTaskServiceImplTest.java src/test/java/com/ruanzhu/doorhandlecatch/service/detection/DetectionTaskEventPublisherTest.java
git commit -m "fix: make detection dispatch idempotent"
```

### Task 5: Commit Kafka offsets only after finished-event delivery

**Files:**
- Modify: `kafka_event_models.py`
- Modify: `kafka_settings.py`
- Modify: `kafka_detection_worker.py`
- Modify: `.env.example`
- Modify: `tests_python/test_kafka_event_models.py`
- Modify: `tests_python/test_kafka_detection_worker.py`

- [ ] **Step 1: Write failing worker delivery tests**

Create fake producer and consumer objects and test the wished-for helper:

```python
def test_publish_success_commits_source_message():
    producer = FakeProducer(delivery_error=None, remaining=0)
    consumer = FakeConsumer()

    publish_finished_and_commit(
        producer, consumer, source_message="source", topic="finished",
        key=b"task-1", payload=b"{}", timeout_seconds=2.0,
    )

    assert consumer.committed == ["source"]


def test_delivery_failure_does_not_commit():
    producer = FakeProducer(delivery_error=RuntimeError("broker down"), remaining=0)
    consumer = FakeConsumer()

    with pytest.raises(RuntimeError, match="finished event delivery failed"):
        publish_finished_and_commit(
            producer, consumer, source_message="source", topic="finished",
            key=b"task-1", payload=b"{}", timeout_seconds=2.0,
        )

    assert consumer.committed == []
```

Add a non-zero `flush` remaining test and event-model assertions that `dispatchId` survives created-event parsing and finished-event serialization.

- [ ] **Step 2: Run the tests and verify RED**

Run: `D:\ruanjian\anaconda3\envs\leetcode\python.exe -m pytest tests_python/test_kafka_event_models.py tests_python/test_kafka_detection_worker.py -q`

Expected: import or assertion failure because the delivery helper and dispatch fields are absent.

- [ ] **Step 3: Implement delivery acknowledgement and dispatch propagation**

Add `dispatch_id` to both dataclasses, parse `dispatchId`, and serialize it. Build finished IDs with:

```python
event_id=f"{task.task_id}-{task.dispatch_id}-finished"
```

Implement the helper:

```python
def publish_finished_and_commit(producer, consumer, source_message, topic,
                                key, payload, timeout_seconds):
    delivery = {"completed": False, "error": None}

    def on_delivery(error, _message):
        delivery["completed"] = True
        delivery["error"] = error

    producer.produce(topic, key=key, value=payload, callback=on_delivery)
    remaining = producer.flush(timeout_seconds)
    if remaining != 0 or not delivery["completed"] or delivery["error"] is not None:
        raise RuntimeError(f"finished event delivery failed: {delivery['error'] or remaining}")
    consumer.commit(message=source_message)
```

Replace the direct `produce/flush/commit` block in `run_worker` with this helper. Let its exception escape so the process exits without committing. Add `KAFKA_DELIVERY_TIMEOUT_SECONDS=10` to `.env.example` and parse a positive float in `KafkaWorkerSettings`.

- [ ] **Step 4: Run worker tests and verify GREEN**

Run: `D:\ruanjian\anaconda3\envs\leetcode\python.exe -m pytest tests_python/test_kafka_event_models.py tests_python/test_kafka_detection_worker.py -q`

Expected: all Kafka model and worker tests pass.

- [ ] **Step 5: Commit this task**

```powershell
git add kafka_event_models.py kafka_settings.py kafka_detection_worker.py .env.example tests_python/test_kafka_event_models.py tests_python/test_kafka_detection_worker.py
git commit -m "fix: acknowledge Kafka finished delivery"
```

### Task 6: Run regression and runtime verification

**Files:**
- Modify only files required by a demonstrated regression.

- [ ] **Step 1: Run the full Java suite**

Run: `.\mvnw.cmd -q test`

Expected: exit code 0 with no failed tests.

- [ ] **Step 2: Run all frontend contracts**

Run from `frontend`:

```powershell
$failed = @()
Get-ChildItem tests -Filter '*.cjs' | ForEach-Object {
    node $_.FullName
    if ($LASTEXITCODE -ne 0) { $failed += $_.Name }
}
if ($failed.Count -gt 0) { throw "Failed contracts: $($failed -join ', ')" }
```

Expected: no failed contract files.

- [ ] **Step 3: Build the frontend**

Run: `npm run build` from `frontend`.

Expected: Vite exits 0 and produces `dist`.

- [ ] **Step 4: Run non-browser Python tests**

Run: `D:\ruanjian\anaconda3\envs\leetcode\python.exe -m pytest tests_python/test_kafka_event_models.py tests_python/test_kafka_detection_worker.py tests_python/test_asr_service.py -q`

Expected: all selected unit tests pass without launching Playwright.

- [ ] **Step 5: Restart the backend and verify roles and protected preview**

Restart Spring Boot, then verify:

- `admin` can list all detection tasks.
- an OPERATOR account receives only its own tasks and receives 403/business denial for a foreign task ID.
- an unauthenticated `/api/oss/preview?key=detection/...` request returns 401/403.
- an authenticated owner receives a redirect for a referenced key.
- repeated `/uploaded` calls expose one `dispatchId` and cause one Kafka publish attempt.

- [ ] **Step 6: Verify repository consistency**

Run: `git diff --check`

Expected: no whitespace errors. Existing CRLF conversion warnings may remain informational.

