const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const composableSource = readFrontendFile('src', 'composables', 'useQualityQueue.js')
const imageDetectionSource = readFrontendFile('src', 'views', 'ImageDetection.vue')

for (const name of [
  'activeQualityQueue',
  'qualityQueueLoading',
  'qualityQueueRecords',
  'qualityQueueTotal',
  'qualityQueuePage',
  'qualityQueuePageSize',
  'qualityQueues',
  'loadQualityQueue',
  'switchQualityQueue',
  'onQualityQueueSizeChange'
]) {
  assert.match(composableSource, new RegExp(name))
  assert.match(imageDetectionSource, new RegExp(name))
}

assert.match(composableSource, /fetchQualityQueue/)
assert.match(composableSource, /normalizeQualityQueueRecord/)
assert.match(composableSource, /PENDING_REVIEW/)
assert.match(composableSource, /REWORK_REQUIRED/)
assert.match(composableSource, /RECHECK_REQUIRED/)
assert.match(imageDetectionSource, /useQualityQueue\(\)/)

assert.doesNotMatch(imageDetectionSource, /fetchQualityQueue/)
assert.doesNotMatch(imageDetectionSource, /const loadQualityQueue = async/)
assert.doesNotMatch(imageDetectionSource, /const activeQualityQueue = ref/)
assert.doesNotMatch(imageDetectionSource, /const qualityQueues = \[/)

console.log('use quality queue contract assertions passed')
