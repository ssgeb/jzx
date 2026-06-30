# Local Faster-Whisper Voice Input Design

## Goal

Turn the existing browser recording UI into a working local speech-to-text
pipeline and ensure every recording outcome leaves the UI in a stable state.

## Architecture

The browser continues to record with `MediaRecorder` and uploads one audio file
to Spring Boot. Spring Boot validates the upload, normalizes MIME parameters,
and forwards it only to the configured allow-listed ASR endpoint. A separate
FastAPI service runs in the repository's `leetcode` Conda environment on
`127.0.0.1:9001` and uses `faster-whisper` with the multilingual `base` model,
CPU execution, and INT8 compute.

The ASR service is isolated from the Java process so model memory and native
dependencies cannot destabilize the application backend. Its model is loaded
once at startup, and temporary audio files are removed after each request.

## Components

- `asr_service.py`: FastAPI health and transcription endpoints, upload limits,
  temporary-file cleanup, Chinese language hint, VAD filtering, and concise
  JSON responses.
- `requirements-asr.txt`: pinned runtime dependencies for the `leetcode`
  environment.
- `scripts/start-asr.ps1`: starts the ASR service through the canonical project
  Python runner and exposes model/device settings through environment variables.
- Spring Boot speech forwarding: accepts MIME values such as
  `audio/webm;codecs=opus` by comparing the normalized media type.
- Vue composer: clears the recognizing state and replaces the progress hint on
  empty text, request failure, timeout, and component teardown.

## Runtime Flow

1. The user records audio and explicitly stops recording.
2. The browser waits for the recorder's final data event and uploads the blob.
3. Spring Boot validates size and normalized MIME type, then forwards the file
   to the local allow-listed ASR endpoint.
4. Faster-whisper decodes the audio with language `zh`, VAD enabled, and an
   industrial inspection vocabulary prompt.
5. Recognized text is returned to the input box but is never sent automatically.

## Failure Handling

- Empty or very short recordings produce a retry hint rather than leaving
  “正在识别语音...” active.
- Unsupported formats return a specific validation message.
- Missing or unhealthy ASR service returns a clear local-service message.
- Frontend and backend timeouts are finite and always release loading state.
- Temporary files are created under the system temporary directory and deleted
  in a `finally` block.
- The service binds to loopback by default and does not expose a shell, file
  browsing, dependency installation, or arbitrary URL access endpoint.

## Performance

The default `base` CPU INT8 model prioritizes predictable Windows startup and
low integration risk. Environment variables can later switch to CUDA without
changing API contracts. The model is loaded before the health endpoint reports
ready, preventing the first user request from absorbing model initialization.

## Verification

- Java unit test accepts `audio/webm;codecs=opus` and still rejects unsupported
  media types.
- Frontend contract verifies empty and error paths clear transcription state and
  update the hint.
- Python tests cover ASR health, empty upload, oversized upload, successful
  transcription response shape, and temporary-file cleanup with a fake model.
- Start the ASR service, verify `/health`, restart Spring Boot with the local URL,
  and submit a generated non-empty audio sample through the full HTTP chain.
- Confirm the frontend never remains in recognizing state after empty text,
  validation failure, network failure, or timeout.

## Non-Goals

- Automatically sending recognized text to the assistant.
- Exposing the ASR service outside the local machine.
- Training or fine-tuning a custom speech model.
- Requiring CUDA for the initial implementation.
