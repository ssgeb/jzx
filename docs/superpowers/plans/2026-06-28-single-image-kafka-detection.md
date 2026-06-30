# Single Image Kafka Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route active-workbench single-image detection through the existing OSS and Kafka worker pipeline and display only remote, traceable results.

**Architecture:** Add a focused frontend orchestration service that creates a `SINGLE` task, uploads one file to its presigned OSS URL, confirms upload, and polls the existing task APIs. Extend the Python worker statistics with a normalized `singleImageResult` summary so the frontend can map category and confidence without guessing or local fallback.

**Tech Stack:** Vue 3, Axios, Spring Boot task APIs, Kafka, Python worker, OSS, Node contract tests, pytest, Playwright

---

### Task 1: Normalize the worker's single-image result

**Files:**
- Create: `tests_python/test_kafka_detection_worker.py`
- Modify: `kafka_detection_worker.py:44-82`
- Modify: `kafka_detection_worker.py:85-115`

- [ ] **Step 1: Write failing worker tests**

```python
from kafka_detection_worker import build_defect_evidence, build_statistics


def test_single_image_statistics_expose_remote_category_and_confidence():
    statistics = build_statistics(
        [{
            "fileName": "door.jpg",
            "detections": [
                {"label": "Bent", "score": 0.73, "box": [10, 20, 60, 80]},
                {"label": "Rusty", "score": 0.91, "box": [15, 25, 70, 95]},
            ],
        }],
        failed_images=0,
    )

    assert statistics["singleImageResult"] == {
        "category": "Rusty",
        "confidence": 0.91,
    }


def test_defect_evidence_accepts_worker_score_and_list_box():
    evidence = build_defect_evidence(
        [{
            "fileName": "door.jpg",
            "sourceKey": "source/door.jpg",
            "previewKey": "result/door.jpg",
            "detections": [
                {"label": "Bent", "score": 0.73, "box": [10, 20, 60, 80]},
            ],
        }]
    )

    assert evidence[0]["confidence"] == 0.73
    assert evidence[0]["area"] == 3000.0
```

- [ ] **Step 2: Run the tests and verify RED**

Run: `conda run --no-capture-output -n leetcode pytest tests_python/test_kafka_detection_worker.py -q`

Expected: FAIL because `singleImageResult` is absent and evidence confidence is `None`.

- [ ] **Step 3: Add a normalized single-image summary**

Add this helper and call it from `build_statistics` only when exactly one image was processed:

```python
def build_single_image_result(per_image_results: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if len(per_image_results) != 1:
        return None
    detections = per_image_results[0].get("detections", [])
    if not detections:
        return None
    top_detection = max(
        detections,
        key=lambda item: float(item.get("confidence", item.get("score", 0.0)) or 0.0),
    )
    confidence = top_detection.get("confidence", top_detection.get("score"))
    category = str(
        top_detection.get("defectType")
        or top_detection.get("label")
        or top_detection.get("category")
        or ""
    ).strip()
    if not category or confidence is None:
        return None
    return {"category": category, "confidence": float(confidence)}
```

After building class counts:

```python
single_image_result = build_single_image_result(per_image_results)
if single_image_result is not None:
    statistics["singleImageResult"] = single_image_result
```

- [ ] **Step 4: Normalize evidence confidence and area**

Use `confidence` with `score` fallback and calculate area for both map and list boxes:

```python
confidence = detection.get("confidence", detection.get("score"))
area = detection.get("area")
if area is None and isinstance(bbox, dict):
    width = float(bbox.get("width") or bbox.get("w") or 0)
    height = float(bbox.get("height") or bbox.get("h") or 0)
    area = width * height
elif area is None and isinstance(bbox, (list, tuple)) and len(bbox) >= 4:
    area = abs(float(bbox[2]) - float(bbox[0])) * abs(float(bbox[3]) - float(bbox[1]))
```

Store `confidence` in the evidence map.

- [ ] **Step 5: Run worker tests and verify GREEN**

Run: `conda run --no-capture-output -n leetcode pytest tests_python/test_kafka_detection_worker.py tests_python/test_kafka_event_models.py -q`

Expected: all tests pass.

### Task 2: Add a single-image Kafka orchestration service

**Files:**
- Create: `frontend/src/services/singleImageKafkaDetection.js`
- Modify: `frontend/tests/single-image-preview-contract.test.cjs`

- [ ] **Step 1: Add failing frontend contract assertions**

```js
const kafkaService = read('src', 'services', 'singleImageKafkaDetection.js')
assert.match(kafkaService, /taskType:\s*'SINGLE'/)
assert.match(kafkaService, /\/api\/detection\/tasks/)
assert.match(kafkaService, /\/uploaded/)
assert.match(kafkaService, /singleImageResult/)
assert.match(kafkaService, /远程结果不完整/)
assert.doesNotMatch(activeDetectionPage, /detectSingleImage/)
```

- [ ] **Step 2: Run the contract and verify RED**

Run: `cd frontend && node tests/single-image-preview-contract.test.cjs`

Expected: FAIL because `singleImageKafkaDetection.js` does not exist.

- [ ] **Step 3: Implement the remote orchestration service**

Create a service with this public API:

```js
import request from '../api/request'

const POLL_INTERVAL_MS = 1500
const POLL_TIMEOUT_MS = 120000
const wait = (milliseconds) => new Promise(resolve => setTimeout(resolve, milliseconds))

const buildCaptureInfo = (file) => ({
  captureDate: new Date().toISOString().slice(0, 10),
  region: '单图检测',
  collector: localStorage.getItem('username') || 'web-user',
  deviceName: 'WEB-SINGLE-UPLOAD',
  imageFolderName: `single-${file.name}`
})

const toOssProxyUrl = (putUrl) => {
  const url = new URL(putUrl)
  return `/oss-upload${url.pathname}${url.search}`
}

const mapCompletedResult = (taskId, result) => {
  const summary = result.statistics?.singleImageResult
  const preview = result.previewImages?.[0]
  if (!summary?.category || summary.confidence === null || summary.confidence === undefined || !preview?.annotatedUrl) {
    throw new Error(`远程结果不完整，任务编号：${taskId}`)
  }
  return {
    taskId,
    category: summary.category,
    confidence: Number(summary.confidence),
    annotatedImagePath: preview.annotatedUrl,
    processedImagePath: preview.originalUrl || '',
    source: 'KAFKA_REMOTE'
  }
}

export const detectSingleImageViaKafka = async ({ file, modelId, threshold, onStatus }) => {
  const createResponse = await request.post('/api/detection/tasks', {
    taskType: 'SINGLE',
    modelId: modelId || null,
    threshold,
    captureInfo: buildCaptureInfo(file),
    files: [{
      fileName: file.name,
      contentType: file.type || 'application/octet-stream',
      relativePath: file.name,
      fileSize: file.size
    }]
  })
  const task = createResponse.data?.data
  const upload = task?.uploadUrls?.[0]
  if (!task?.taskId || !upload?.putUrl || !upload?.objectKey) {
    throw new Error('创建单图 Kafka 任务失败：上传信息不完整')
  }
  onStatus?.({ taskId: task.taskId, stage: 'UPLOADING', message: '正在上传原图到 OSS' })
  await request.put(toOssProxyUrl(upload.putUrl), file, {
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
    timeout: 30000
  })
  await request.post(`/api/detection/tasks/${task.taskId}/uploaded`, {
    modelId: modelId || null,
    threshold,
    uploadedFiles: [{ fileName: upload.fileName, objectKey: upload.objectKey }]
  })

  const deadline = Date.now() + POLL_TIMEOUT_MS
  while (Date.now() < deadline) {
    const progressResponse = await request.get(`/api/detection/tasks/${task.taskId}`)
    const progress = progressResponse.data?.data || {}
    onStatus?.({ taskId: task.taskId, stage: progress.stage || progress.status, message: progress.message || '' })
    if (progress.status === 'FAILED') {
      throw new Error(`${progress.errorMessage || progress.message || 'Kafka 远程检测失败'}，任务编号：${task.taskId}`)
    }
    if (progress.status === 'COMPLETED' || progress.status === 'PARTIAL_FAILED') {
      const resultResponse = await request.get(`/api/detection/tasks/${task.taskId}/result`)
      return mapCompletedResult(task.taskId, resultResponse.data?.data || {})
    }
    await wait(POLL_INTERVAL_MS)
  }
  throw new Error(`Kafka 远程检测超时，任务编号：${task.taskId}`)
}
```

- [ ] **Step 4: Run the contract and verify the service assertions pass**

Run: `cd frontend && node tests/single-image-preview-contract.test.cjs`

Expected: the new service assertions pass while the page assertion still fails until Task 3.

### Task 3: Connect the result panel to Kafka task states

**Files:**
- Modify: `frontend/src/views/ImageDetection.vue:85-130`
- Modify: `frontend/src/views/ImageDetection.vue:1440-1470`
- Modify: `frontend/src/views/ImageDetection.vue:1540-1560`
- Modify: `frontend/src/views/ImageDetection.vue:1780-1820`
- Modify: `frontend/tests/single-image-preview-contract.test.cjs`

- [ ] **Step 1: Replace the local API import**

Remove `detectSingleImage` from `../api/detection` and add:

```js
import { detectSingleImageViaKafka } from '../services/singleImageKafkaDetection'
```

- [ ] **Step 2: Add remote task UI state**

```js
const singleDetectionTaskId = ref('')
const singleDetectionStage = ref('IDLE')
const singleDetectionMessage = ref('')
```

Update the upload preview composable call to clear these values through an `onReset` callback, extending that composable with an optional callback:

```js
onReset: () => {
  singleDetectionTaskId.value = ''
  singleDetectionStage.value = 'IDLE'
  singleDetectionMessage.value = ''
}
```

- [ ] **Step 3: Replace the click handler with Kafka orchestration**

```js
const handleSingleDetection = async () => {
  if (!form.imageFile) {
    ElMessage.error('请先选择图片')
    return
  }
  loadingSingle.value = true
  detectionResult.value = null
  annotatedImageUrl.value = ''
  singleDetectionStage.value = 'CREATING'
  singleDetectionMessage.value = '正在创建单图 Kafka 任务'
  try {
    const result = await detectSingleImageViaKafka({
      file: form.imageFile,
      modelId: form.modelId,
      threshold: form.threshold,
      onStatus: ({ taskId, stage, message }) => {
        singleDetectionTaskId.value = taskId || singleDetectionTaskId.value
        singleDetectionStage.value = stage || singleDetectionStage.value
        singleDetectionMessage.value = message || singleDetectionMessage.value
      }
    })
    detectionResult.value = result
    annotatedImageUrl.value = result.annotatedImagePath
    singleDetectionTaskId.value = result.taskId
    singleDetectionStage.value = 'COMPLETED'
    singleDetectionMessage.value = 'Kafka 远程检测完成'
    ElMessage.success('单图 Kafka 远程检测完成')
  } catch (error) {
    singleDetectionStage.value = 'FAILED'
    singleDetectionMessage.value = error.message || 'Kafka 远程检测失败'
    ElMessage.error(singleDetectionMessage.value)
  } finally {
    loadingSingle.value = false
  }
}
```

- [ ] **Step 4: Show task source and failure details in the result pane**

Add a `Kafka 远程` source tag, task ID, current stage message, and a danger alert for `FAILED`. Keep the existing selected-image preview when no completed result exists. The task ID must use:

```vue
<code v-if="singleDetectionTaskId" class="single-result-task-id">
  {{ singleDetectionTaskId }}
</code>
```

The failure state must use:

```vue
<el-alert
  v-if="singleDetectionStage === 'FAILED'"
  type="error"
  :closable="false"
  show-icon
  :title="singleDetectionMessage"
/>
```

- [ ] **Step 5: Make reset callbacks explicit**

Modify `useSingleImageUploadPreview` to accept `onReset = () => {}` and invoke it from both `handleUploadChange` and `handleRemove` after clearing result values.

- [ ] **Step 6: Run the contract and verify GREEN**

Run: `cd frontend && node tests/single-image-preview-contract.test.cjs`

Expected: `single image upload preview contract assertions passed`.

### Task 4: Verify remote-only behavior

**Files:**
- Modify: `frontend/tests/single-image-preview-contract.test.cjs`
- Test: `tests_python/test_kafka_detection_worker.py`

- [ ] **Step 1: Run all Python worker tests**

Run: `conda run --no-capture-output -n leetcode pytest tests_python/test_kafka_detection_worker.py tests_python/test_kafka_event_models.py -q`

Expected: all tests pass.

- [ ] **Step 2: Run all frontend contracts and build**

Run every `frontend/tests/*.test.cjs` file with Node, then run `npm run build` in `frontend`.

Expected: all contract files and the Vite build exit with code 0.

- [ ] **Step 3: Browser-test the completed path with mocked task APIs**

Use Playwright route handlers to return a `SINGLE` task, accept OSS upload, return `PROCESSING` then `COMPLETED`, and provide a result containing:

```json
{
  "statistics": {"singleImageResult": {"category": "Rusty", "confidence": 0.91}},
  "previewImages": [{"annotatedUrl": "/mock/annotated.jpg", "originalUrl": "/mock/original.jpg"}]
}
```

Assert the page displays `锈蚀`, `91.00%`, `Kafka 远程`, and the task ID without requesting `/api/image-detection/upload`.

- [ ] **Step 4: Browser-test the real unavailable-Broker path**

With the current Broker unavailable, run one real single-image attempt and assert the task transitions to `FAILED`, the result panel displays the Kafka failure and task ID, and no `20.00%` result appears.

- [ ] **Step 5: Run patch checks**

Run: `git diff --check`

Expected: exit code 0 with no whitespace errors.

## Operational Constraint

Successful real inference cannot be claimed until Kafka `10.20.144.118:9092` and its Python worker are reachable. The unavailable-Broker failure behavior is part of this implementation and must remain explicit; no local fallback is permitted.
