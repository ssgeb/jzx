# Leetcode Python Environment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `leetcode` Conda environment the deterministic Python runtime for all repository Python tools and Playwright tests.

**Architecture:** A PowerShell wrapper validates Conda and delegates every Python command to `conda run -n leetcode`, preserving the child exit code. Workspace settings and launchers point to the same environment, while Playwright resolves its own installed Chromium instead of using a hard-coded browser path.

**Tech Stack:** Conda, Python 3.10, PowerShell, Playwright 1.60, Node.js contract tests

---

### Task 1: Add Environment Contract Coverage

**Files:**
- Create: `frontend/tests/python-environment-contract.test.cjs`

- [ ] **Step 1: Write the failing contract test**

Create a Node test that reads `environment.yml`, `.vscode/settings.json`,
`scripts/run-python.ps1`, `tests_python/run_test.bat`, and both Playwright Python
scripts. Assert that the environment is named `leetcode`, the wrapper uses
`conda run`, launchers do not contain `executable_path`, and the batch test does
not contain an absolute Anaconda interpreter path.

- [ ] **Step 2: Run the contract test and verify failure**

Run: `node --test frontend/tests/python-environment-contract.test.cjs`

Expected: FAIL because `environment.yml` and `scripts/run-python.ps1` do not yet
exist and Playwright scripts still contain `executable_path`.

- [ ] **Step 3: Keep the failing test as the implementation contract**

The test must use only Node built-ins (`node:test`, `node:assert`, `node:fs`, and
`node:path`) so no new frontend dependency is required.

### Task 2: Add the Canonical Conda Runner and Dependency Manifest

**Files:**
- Create: `scripts/run-python.ps1`
- Create: `environment.yml`

- [ ] **Step 1: Implement the PowerShell runner**

The runner accepts remaining arguments, checks `Get-Command conda`, parses
`conda env list --json`, verifies an environment whose final path segment is
`leetcode`, then executes:

```powershell
& conda run --no-capture-output -n leetcode python @PythonArguments
exit $LASTEXITCODE
```

Missing Conda exits `127`; a missing `leetcode` environment exits `2`; child
process failures retain their original exit code. There is no base fallback.

- [ ] **Step 2: Record the environment dependencies**

Create `environment.yml` with `name: leetcode`, Python `3.10`, pip, Playwright
`1.60.0`, and the three packages currently listed in `requirements-kafka.txt`.

- [ ] **Step 3: Verify interpreter and failure propagation**

Run:

```powershell
& .\scripts\run-python.ps1 -c "import sys; print(sys.executable)"
& .\scripts\run-python.ps1 -c "import sys; sys.exit(7)"
```

Expected: the first path contains `envs\leetcode`; the second command exits `7`.

### Task 3: Route Existing Python Entry Points Through Leetcode

**Files:**
- Modify: `.vscode/settings.json`
- Modify: `tests_python/run_test.bat`
- Modify: `frontend/tests/test_frontend.py`
- Modify: `frontend/tests/test_browser.py`

- [ ] **Step 1: Configure the workspace interpreter**

Add `python.defaultInterpreterPath` with the verified local interpreter
`D:\ruanjian\anaconda3\envs\leetcode\python.exe`, preserving both Java settings.

- [ ] **Step 2: Make the batch launcher portable**

Replace the absolute interpreter invocation with:

```bat
conda run --no-capture-output -n leetcode python "%~dp0test_agent.py"
exit /b %ERRORLEVEL%
```

- [ ] **Step 3: Remove hard-coded Chromium paths**

Change both Python browser launch calls to `p.chromium.launch(headless=True)` so
Playwright uses the browser installed for the active package version.

- [ ] **Step 4: Run the contract test**

Run: `node --test frontend/tests/python-environment-contract.test.cjs`

Expected: PASS.

### Task 4: Document and Verify the Toolchain

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Document setup and canonical commands**

Add Python 3.10/Conda to prerequisites and document environment creation,
Playwright Chromium installation, browser tests, Python tests, and Kafka worker
commands through `scripts/run-python.ps1`.

- [ ] **Step 2: Verify Playwright in the canonical environment**

Run:

```powershell
& .\scripts\run-python.ps1 -c "import importlib.metadata as m; print(m.version('playwright'))"
& .\scripts\run-python.ps1 frontend\tests\test_browser.py
```

Expected: version `1.60.0` and output `Chromium OK`.

- [ ] **Step 3: Run all environment contract tests**

Run: `node --test frontend/tests/python-environment-contract.test.cjs`

Expected: all tests PASS.

### Task 5: Start and Probe the Application

**Files:**
- No source changes expected.

- [ ] **Step 1: Stop only stale listeners on the project ports**

Identify listeners on backend port `8080` and frontend port `3001`, and stop
those processes before restarting. Do not terminate unrelated Java or Node
processes that are not bound to these ports.

- [ ] **Step 2: Start the backend and frontend**

Start Spring Boot from the repository root and Vite from `frontend`, writing
stdout and stderr to the existing project log files.

- [ ] **Step 3: Probe both services**

Wait for port `8080` and port `3001`, then request the frontend root and a safe
backend endpoint. Expected: both ports listen, the frontend returns HTTP 200,
and the backend responds without a connection error.
