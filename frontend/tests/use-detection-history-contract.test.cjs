const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const composableSource = readFrontendFile('src', 'composables', 'useDetectionHistory.js')
const imageDetectionSource = readFrontendFile('src', 'views', 'ImageDetection.vue')

for (const name of [
  'historySearchKeyword',
  'historyFilterCollector',
  'historyFilterDevice',
  'historyFilterRegion',
  'historyPage',
  'historyPageSize',
  'historyTotal',
  'historyRecords',
  'historyLoading',
  'fetchHistoryWithFilters',
  'onHistorySearch',
  'onHistoryPageChange',
  'onHistorySizeChange'
]) {
  assert.match(composableSource, new RegExp(name))
  assert.match(imageDetectionSource, new RegExp(name))
}

assert.match(composableSource, /buildHistoryFilters/)
assert.match(composableSource, /normalizeHistoryRecord/)
assert.match(composableSource, /pollingStore\.fetchTaskList/)
assert.match(composableSource, /pollingStore\.fetchTaskResults/)
assert.match(imageDetectionSource, /useDetectionHistory\(taskStore, pollingStore\)/)

assert.doesNotMatch(imageDetectionSource, /const historySearchKeyword = ref/)
assert.doesNotMatch(imageDetectionSource, /const historyPage = ref/)
assert.doesNotMatch(imageDetectionSource, /const historyRecords = ref/)
assert.doesNotMatch(imageDetectionSource, /const fetchHistoryWithFilters = async/)
assert.doesNotMatch(imageDetectionSource, /const onHistoryPageChange = /)
assert.doesNotMatch(imageDetectionSource, /const completedTasks = computed/)

console.log('use detection history contract assertions passed')
