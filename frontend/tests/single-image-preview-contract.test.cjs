const assert = require('assert')
const fs = require('fs')
const path = require('path')

const read = (...segments) => fs.readFileSync(path.join(__dirname, '..', ...segments), 'utf8')

const composableSource = read('src', 'composables', 'useSingleImageUploadPreview.js')

assert.match(composableSource, /URL\.createObjectURL\(file\)/)
assert.match(composableSource, /URL\.revokeObjectURL/)
assert.match(composableSource, /handleUploadChange/)
assert.match(composableSource, /uploadFile\.raw/)
assert.match(composableSource, /onBeforeUnmount/)
assert.match(composableSource, /onReset/)

const pages = [
  ['src', 'views', 'ImageDetection.vue'],
  ['src', 'views', 'inspection', 'InspectionWorkbench.vue'],
  ['src', 'views', 'inspection', 'InspectionHistory.vue'],
  ['src', 'views', 'quality', 'QualityQueue.vue'],
  ['src', 'views', 'quality', 'DefectEvidenceGallery.vue'],
  ['src', 'views', 'quality', 'WorkOrderTrace.vue'],
  ['src', 'views', 'quality', 'BatchTrace.vue']
]

for (const pagePath of pages) {
  const source = read(...pagePath)
  assert.match(source, /useSingleImageUploadPreview/)
  assert.doesNotMatch(source, /const beforeUpload = \(file\) =>/)
  assert.doesNotMatch(source, /originFileObj:\s*file/)
}

for (const pagePath of pages.slice(0, 2)) {
  const source = read(...pagePath)
  assert.match(source, /:auto-upload="false"/)
  assert.match(source, /:on-change="handleUploadChange"/)
  assert.match(source, /:limit="1"/)
  assert.match(source, /:on-remove="handleRemove"/)
  assert.doesNotMatch(source, /:remove="handleRemove"/)
  assert.doesNotMatch(source, /:before-upload=/)
  assert.doesNotMatch(source, /:max-count=/)
}

const activeDetectionPage = read('src', 'views', 'ImageDetection.vue')
const kafkaServicePath = path.join(__dirname, '..', 'src', 'services', 'singleImageKafkaDetection.js')
assert.ok(fs.existsSync(kafkaServicePath), 'single image Kafka detection service must exist')
const kafkaService = fs.readFileSync(kafkaServicePath, 'utf8')
assert.match(kafkaService, /taskType:\s*'SINGLE'/)
assert.match(kafkaService, /\/api\/detection\/tasks/)
assert.match(kafkaService, /\/uploaded/)
assert.match(kafkaService, /singleImageResult/)
assert.match(kafkaService, /远程结果不完整/)
assert.doesNotMatch(activeDetectionPage, /\bdetectSingleImage(?:\s*,|\s*\()/)
assert.match(activeDetectionPage, /detectSingleImageViaKafka/)
assert.match(activeDetectionPage, /singleDetectionTaskId/)
assert.match(activeDetectionPage, /singleDetectionStage === 'FAILED'/)
assert.match(activeDetectionPage, /Kafka 远程/)
assert.match(activeDetectionPage, /class="single-detection-workspace"/)
assert.match(activeDetectionPage, /class="single-detection-result"/)
assert.match(activeDetectionPage, /class="single-upload-file-name"/)
assert.match(activeDetectionPage, /v-loading="loadingSingle"/)
assert.match(activeDetectionPage, /alt="单图检测标注结果"/)
assert.match(activeDetectionPage, /alt="待检测图片预览"/)
assert.match(activeDetectionPage, /请先选择图片并开始检测/)
assert.doesNotMatch(activeDetectionPage, /class="detection-card result-card"/)
assert.doesNotMatch(activeDetectionPage, /singleResultCard/)
assert.doesNotMatch(activeDetectionPage, /scrollIntoView/)

console.log('single image upload preview contract assertions passed')
