# Follow-up Security Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复流式助手异步线程丢失认证上下文，以及 OSS 预览在重复对象 key 场景下误拒绝所有者并扫描全表的问题。

**Architecture:** 流式编排提交任务时复制当前 Spring Security `SecurityContext`，在工作线程完成后由 Spring Security 包装器恢复原上下文。OSS 授权先在数据库层按当前用户和对象 key 缩小候选集，再保留 Java 端 JSON 精确匹配作为二次校验。

**Tech Stack:** Java 17、Spring Boot 3.2、Spring Security 6、MyBatis-Plus、JUnit 5、Mockito、AssertJ

**Workspace note:** 按当前会话约定直接在现有脏工作区实施，不创建 worktree、不提交，避免覆盖用户已有改动。

---

### Task 1: Preserve authentication in streaming chat workers

**Files:**
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImpl.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/AgentOrchestratorServiceImplTest.java`

- [x] **Step 1: Write the failing test**

新增测试：请求线程以 `alice/ROLE_OPERATOR` 登录，`TaskExecutor` 只捕获任务而不立即执行；调用 `streamUserMessage` 后清空调用线程上下文，再执行捕获任务，并在 `chatSessionService.verifySessionOwner` 的 mock 回调中断言当前认证仍是 `alice`。

- [x] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -q '-Dtest=AgentOrchestratorServiceImplTest#streamUserMessagePropagatesAuthenticationToWorker' test`

Expected: FAIL，因为工作线程观察到的认证为空。

- [x] **Step 3: Write minimal implementation**

在 `streamUserMessage` 提交任务前获取当前 `SecurityContext`，使用 `DelegatingSecurityContextRunnable` 包装现有 SSE 工作任务后交给 `TaskExecutor`。

- [x] **Step 4: Run focused tests**

Run: `./mvnw.cmd -q '-Dtest=AgentOrchestratorServiceImplTest' test`

Expected: PASS。

### Task 2: Scope OSS preview candidates before exact validation

**Files:**
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/OssPreviewAuthorizationService.java`
- Test: `src/test/java/com/ruanzhu/doorhandlecatch/service/OssPreviewAuthorizationServiceTest.java`

- [x] **Step 1: Write the failing test**

新增测试：mapper 返回先属于 `bob`、后属于 `alice` 且都引用同一 key 的任务，`alice` 授权应成功；同时捕获 `LambdaQueryWrapper`，断言 SQL 片段包含 `created_by` 和三个 OSS key 字段的候选条件。

- [x] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -q '-Dtest=OssPreviewAuthorizationServiceTest#ownerCanPreviewWhenForeignTaskReferencesSameKey' test`

Expected: FAIL，当前实现选择第一条外部用户任务并返回 403。

- [x] **Step 3: Write minimal implementation**

构建 MyBatis-Plus 查询条件：非管理员增加 `created_by = authentication.name`；候选 key 使用 `result_json_oss_key = key` 或两个 JSON 文本列 `LIKE key`；结果仍通过 `references` 做精确字符串比较，并对最终任务调用统一访问策略。

- [x] **Step 4: Run focused tests**

Run: `./mvnw.cmd -q '-Dtest=OssPreviewAuthorizationServiceTest,DetectionTaskAccessPolicyTest' test`

Expected: PASS。

### Task 3: Full regression verification

**Files:**
- Verify only; no production file changes expected.

- [x] **Step 1: Run Java test suite**

Run: `./mvnw.cmd test`

Expected: BUILD SUCCESS，零失败、零错误。

- [x] **Step 2: Run Python and frontend checks**

Run: `./tests_python/run_test.bat`

Run from `frontend`: `node --test tests/*.test.cjs`

Run from `frontend`: `npm run build`

Expected: 全部退出码为 0。

- [x] **Step 3: Check patch hygiene**

Run: `git diff --check`

Expected: 无空白错误。
