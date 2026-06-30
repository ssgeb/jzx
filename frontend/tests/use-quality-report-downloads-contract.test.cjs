const assert = require('assert')
const fs = require('fs')
const path = require('path')

const read = (...segments) => fs.readFileSync(path.join(__dirname, '..', ...segments), 'utf8')

const composableSource = read('src', 'composables', 'useQualityReportDownloads.js')
const imageDetectionSource = read('src', 'views', 'ImageDetection.vue')

for (const name of [
  'safeFilePart',
  'reportTimestamp',
  'downloadJsonReport',
  'reportDownloading',
  'downloadTaskQualityReport',
  'downloadBatchTraceReport',
  'downloadWorkOrderTraceReport'
]) {
  assert.match(composableSource, new RegExp(name))
}

for (const name of [
  'reportDownloading',
  'downloadTaskQualityReport',
  'downloadBatchTraceReport',
  'downloadWorkOrderTraceReport'
]) {
  assert.match(imageDetectionSource, new RegExp(name))
}

assert.match(composableSource, /fetchQualityReport/)
assert.match(composableSource, /quality-report-\$\{safeFilePart\(taskId\)\}-\$\{reportTimestamp\(\)\}\.json/)
assert.match(composableSource, /batch-trace-\$\{safeFilePart\(batchNo\)\}-\$\{reportTimestamp\(\)\}\.json/)
assert.match(composableSource, /work-order-trace-\$\{safeFilePart\(workOrderNo\)\}-\$\{reportTimestamp\(\)\}\.json/)
assert.match(imageDetectionSource, /useQualityReportDownloads\(\{/)

assert.doesNotMatch(imageDetectionSource, /fetchQualityReport/)
assert.doesNotMatch(imageDetectionSource, /const safeFilePart = /)
assert.doesNotMatch(imageDetectionSource, /const reportTimestamp = /)
assert.doesNotMatch(imageDetectionSource, /const downloadJsonReport = /)
assert.doesNotMatch(imageDetectionSource, /const downloadTaskQualityReport = async/)
assert.doesNotMatch(imageDetectionSource, /const downloadBatchTraceReport = /)
assert.doesNotMatch(imageDetectionSource, /const downloadWorkOrderTraceReport = /)

console.log('use quality report downloads contract assertions passed')
