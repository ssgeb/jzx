# Responsive Assistant and UTF-8 Seeds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the assistant visible at every supported viewport and repair UTF-8 business seed data without destructive database updates.

**Architecture:** Vue components measure and clamp persisted UI dimensions whenever the viewport changes; the application shell adds fluid sizing without changing its visual system. Spring's manually constructed SQL populators explicitly decode scripts as UTF-8, and the existing idempotent seed upserts repair affected rows on restart.

**Tech Stack:** Vue 3, Element Plus, CSS media queries and `clamp()`, Node contract tests, Spring Boot JDBC, JUnit 5, MySQL 8.3, Playwright Python

---

### Task 1: Add Failing Responsive Assistant Contracts

**Files:**
- Create: `frontend/tests/chat-responsive-viewport-contract.test.cjs`
- Test: `frontend/tests/chat-responsive-viewport-contract.test.cjs`

- [ ] **Step 1: Write source contracts for viewport-safe behavior**

The Node test reads `ChatAssistantLauncher.vue`, `ChatAssistantDrawer.vue`, and
`layout/index.vue`. It asserts the launcher has a template ref, mount/resize
clamping, and cleanup; the drawer tracks viewport width and clamps saved width;
the shell uses fluid `clamp()` sizing.

- [ ] **Step 2: Run the contract and confirm RED**

Run: `node --test frontend/tests/chat-responsive-viewport-contract.test.cjs`

Expected: FAIL because the launcher does not clamp on load/resize and the drawer
uses a module-time `MAX_WIDTH` constant.

### Task 2: Keep the Launcher Inside the Viewport

**Files:**
- Modify: `frontend/src/components/chat/ChatAssistantLauncher.vue`
- Test: `frontend/tests/chat-responsive-viewport-contract.test.cjs`

- [ ] **Step 1: Add an element ref and coordinate clamp**

Use `launcherRef`, `onMounted`, and `nextTick`. Implement `clampToViewport()`
with a 12-pixel safe inset:

```js
const maxX = Math.max(SAFE_INSET, window.innerWidth - rect.width - SAFE_INSET)
const maxY = Math.max(SAFE_INSET, window.innerHeight - rect.height - SAFE_INSET)
posX.value = Math.min(Math.max(posX.value, SAFE_INSET), maxX)
posY.value = Math.min(Math.max(posY.value, SAFE_INSET), maxY)
```

Only persisted custom positions are clamped; the default CSS bottom-right
position remains unchanged. Corrected coordinates are saved.

- [ ] **Step 2: Clamp after drag and viewport resize**

Register `window.addEventListener('resize', clampToViewport)` on mount, call the
same function after a drag, and remove the listener on unmount.

- [ ] **Step 3: Run the responsive contract**

Run: `node --test frontend/tests/chat-responsive-viewport-contract.test.cjs`

Expected: launcher assertions PASS; drawer assertions remain RED.

### Task 3: Make Drawer and Shell Dimensions Responsive

**Files:**
- Modify: `frontend/src/components/chat/ChatAssistantDrawer.vue`
- Modify: `frontend/src/layout/index.vue`
- Test: `frontend/tests/chat-responsive-viewport-contract.test.cjs`

- [ ] **Step 1: Replace fixed drawer bounds with viewport state**

Track `viewportWidth` in a ref. Compute mobile mode at `768` pixels, compute a
full-width mobile drawer and a desktop maximum of `min(1200, viewport * 0.75)`,
and expose `drawerSize` to `<el-drawer>`.

- [ ] **Step 2: Re-clamp saved width on resize**

`handleViewportResize()` updates `viewportWidth`, clamps `panelWidth`, and saves
the corrected value. Disable the resize handle on mobile with `v-if="!isMobile"`.

- [ ] **Step 3: Add fluid shell sizing**

Use `clamp()` for shell padding/gap, header spacing, and desktop title size.
Preserve the existing `1100px` and `768px` layout transitions.

- [ ] **Step 4: Run contracts and frontend build**

Run:

```powershell
node --test frontend/tests/chat-responsive-viewport-contract.test.cjs
npm --prefix frontend run build
```

Expected: contract PASS and Vite build exits `0`.

### Task 4: Force UTF-8 in Custom SQL Populators

**Files:**
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/config/BusinessSeedDataConfig.java`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/config/DatabaseInitConfig.java`
- Create: `src/test/java/com/ruanzhu/doorhandlecatch/config/SqlPopulatorEncodingContractTest.java`

- [ ] **Step 1: Write the failing Java source contract**

Read both Java source files as UTF-8 and assert each contains:

```java
populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
```

The test also asserts both files import `java.nio.charset.StandardCharsets`.

- [ ] **Step 2: Run the test and confirm RED**

Run: `.\mvnw.cmd -Dtest=SqlPopulatorEncodingContractTest test`

Expected: FAIL because neither custom populator sets its encoding.

- [ ] **Step 3: Set encoding in both configurations**

Add the `StandardCharsets` import and call `setSqlScriptEncoding` immediately
after constructing each `ResourceDatabasePopulator`.

- [ ] **Step 4: Run the focused Java test**

Run: `.\mvnw.cmd -Dtest=SqlPopulatorEncodingContractTest test`

Expected: PASS.

### Task 5: Restart, Repair, and Verify

**Files:**
- No additional source changes expected.

- [ ] **Step 1: Restart backend with business seeding enabled**

Stop only the process listening on `8080`, run
`scripts/start-backend-with-business-seed.ps1 -ContinueOnError`, and wait for
Hikari plus the business seed completion log entries.

- [ ] **Step 2: Query repaired regions**

Using credentials from `.env` through `MYSQL_PWD`, query grouped region values
and assert these values have count zero:

```text
涓婃捣 澶╂触 鍗椾含 骞垮窞 鎴愰兘
```

Confirm `上海`, `天津`, `南京`, `广州`, and `成都` are present with valid UTF-8
hexadecimal bytes.

- [ ] **Step 3: Run multi-viewport Playwright verification**

Authenticate through the backend, inject an off-screen launcher position, and
verify its bounding box after reload at widths `1920`, `1280`, `768`, and `375`.
Open the drawer at each width and assert its right edge does not exceed the
viewport and `document.documentElement.scrollWidth <= window.innerWidth`.

- [ ] **Step 4: Run final checks**

Run the responsive Node contract, focused Java test, Vite production build,
HTTP probes for ports `3001` and `8080`, and scoped `git diff --check`.
