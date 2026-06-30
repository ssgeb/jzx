# Local Faster-Whisper Voice Input Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide reliable local Chinese speech recognition and guarantee that the assistant voice UI exits recording and transcription states on every path.

**Architecture:** A loopback-only FastAPI process in the `leetcode` Conda environment owns faster-whisper and exposes health/transcription endpoints. Spring Boot validates and forwards audio to that endpoint, while Vue records audio and treats success, empty speech, timeout, and error as explicit terminal states.

**Tech Stack:** Vue 3, Axios, Spring Boot 3, JUnit 5, FastAPI, faster-whisper, pytest, Playwright, Conda Python 3.10

---

### Task 1: Lock Down Existing Frontend and Java Defects

**Files:**
- Modify: `frontend/tests/chat-voice-input-contract.test.cjs`
- Modify: `src/test/java/com/ruanzhu/doorhandlecatch/service/impl/SpeechTranscriptionServiceImplTest.java`

- [ ] Add frontend assertions that empty text sets a retry hint and the `finally`
  path always clears `voiceTranscribing` and audio chunks.
- [ ] Add a Java test that submits `audio/webm;codecs=opus` to a mocked allowed
  ASR endpoint and expects recognized text.
- [ ] Run both tests and confirm they fail for the current defects.

Commands:

```powershell
node frontend/tests/chat-voice-input-contract.test.cjs
.\mvnw.cmd -Dtest=SpeechTranscriptionServiceImplTest test
```

### Task 2: Fix Browser State and MIME Validation

**Files:**
- Modify: `frontend/src/components/chat/ChatComposer.vue`
- Modify: `src/main/java/com/ruanzhu/doorhandlecatch/service/impl/SpeechTranscriptionServiceImpl.java`

- [ ] Normalize backend content types by removing parameters after `;`, trimming,
  and lower-casing before allow-list comparison.
- [ ] Replace the empty-result progress hint with
  `未识别到语音，请靠近麦克风后重试`.
- [ ] Ensure unmount also clears `voiceTranscribing`, chunks, recorder callbacks,
  timers, and media tracks.
- [ ] Run the focused frontend and Java tests and confirm PASS.

### Task 3: Build the Testable ASR Service

**Files:**
- Create: `tests_python/test_asr_service.py`
- Create: `asr_service.py`
- Create: `requirements-asr.txt`

- [ ] Install FastAPI test dependencies in `leetcode` without changing project
  source, then write tests against `create_app(model_loader)` using a fake model.
- [ ] Verify tests fail because `asr_service` does not exist.
- [ ] Implement a FastAPI lifespan that loads one model, `/health`, and
  `/transcribe` with a bounded upload read, safe suffix, temporary-file cleanup,
  `language="zh"`, VAD filtering, and an industrial vocabulary prompt.
- [ ] Return `{ "text": value }`; use HTTP 400 for empty/oversized uploads and
  HTTP 503 when the model cannot initialize.
- [ ] Run `pytest tests_python/test_asr_service.py -q` and confirm PASS.

### Task 4: Add Deterministic Startup and Configuration

**Files:**
- Create: `scripts/start-asr.ps1`
- Modify: `scripts/start-backend-with-business-seed.ps1`
- Modify: `.env.example`
- Modify: `docs/voice-input-asr-setup.md`
- Modify: `environment.yml`

- [ ] Start Uvicorn through `scripts/run-python.ps1` on `127.0.0.1:9001`, with
  model/device/compute settings supplied by environment variables.
- [ ] Default the development backend launcher to
  `http://127.0.0.1:9001/transcribe` only when no explicit ASR URL is set.
- [ ] Record ASR dependencies and safe startup commands in configuration/docs.
- [ ] Run the project contracts and scoped `git diff --check`.

### Task 5: Install, Start, and Verify the Full Pipeline

**Files:**
- No additional source changes expected.

- [ ] Install `requirements-asr.txt` into `leetcode` and install/resolve the base
  model during ASR startup.
- [ ] Start ASR hidden with dedicated logs, wait for `/health`, then restart the
  Spring backend so it receives the local transcription URL.
- [ ] Generate a local WAV speech fixture when a Chinese Windows SAPI voice is
  available; otherwise verify the real model with a non-empty audio fixture and
  report the remaining manual microphone check explicitly.
- [ ] Submit through Spring's `/api/chat-assistant/voice/transcribe`, verify the
  request terminates, and confirm the response shape or explicit empty-speech
  result.
- [ ] Run frontend contract/build, Java speech tests, Python ASR tests, health
  probes, and verify ports `9001`, `8080`, and `3001` remain listening.
