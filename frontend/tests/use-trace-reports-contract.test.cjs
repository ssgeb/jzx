const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const composableSource = readFrontendFile('src', 'composables', 'useTraceReports.js')
const imageDetectionSource = readFrontendFile('src', 'views', 'ImageDetection.vue')

for (const name of [
  'workOrderTraceNo',
  'workOrderTraceLoading',
  'workOrderTraceReport',
  'batchTraceNo',
  'batchTraceLoading',
  'batchTraceReport',
  'businessWorkOrderNos',
  'businessBatchNos',
  'ensureDefaultWorkOrderNo',
  'ensureDefaultBatchNo',
  'loadWorkOrderTraceReport',
  'loadBatchTraceReport',
  'loadBusinessWorkOrderTrace',
  'loadBusinessBatchTrace'
]) {
  assert.match(composableSource, new RegExp(name))
  assert.match(imageDetectionSource, new RegExp(name))
}

assert.match(composableSource, /fetchWorkOrderTraceReport/)
assert.match(composableSource, /fetchBatchTraceReport/)
assert.match(composableSource, /businessWorkOrderNos/)
assert.match(composableSource, /businessBatchNos/)
assert.match(imageDetectionSource, /useTraceReports\(\)/)

assert.doesNotMatch(imageDetectionSource, /fetchWorkOrderTraceReport/)
assert.doesNotMatch(imageDetectionSource, /fetchBatchTraceReport/)
assert.doesNotMatch(imageDetectionSource, /const workOrderTraceNo = ref/)
assert.doesNotMatch(imageDetectionSource, /const batchTraceNo = ref/)
assert.doesNotMatch(imageDetectionSource, /const demoWorkOrderNos = \[/)
assert.doesNotMatch(imageDetectionSource, /demoBatchNos/)
assert.doesNotMatch(imageDetectionSource, /loadDemoWorkOrderTrace/)
assert.doesNotMatch(imageDetectionSource, /loadDemoBatchTrace/)
assert.doesNotMatch(imageDetectionSource, /const loadWorkOrderTraceReport = async/)
assert.doesNotMatch(imageDetectionSource, /const loadBatchTraceReport = async/)

console.log('use trace reports contract assertions passed')
