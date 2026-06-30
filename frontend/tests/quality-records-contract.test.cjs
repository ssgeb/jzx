const assert = require('assert')
const fs = require('fs')
const path = require('path')

const read = (...segments) => fs.readFileSync(path.join(__dirname, '..', ...segments), 'utf8')

const recordsSource = read('src', 'utils', 'qualityRecords.js')
const imageDetectionSource = read('src', 'views', 'ImageDetection.vue')

for (const exportName of [
  'normalizeQualityQueueRecord',
  'buildTraceEvidenceRows',
  'resolveDefectPreview',
  'formatEvidenceConfidence',
  'formatPercentValue'
]) {
  assert.match(recordsSource, new RegExp(`export const ${exportName}`))
  assert.match(imageDetectionSource, new RegExp(exportName))
}

assert.match(recordsSource, /captureInfo/)
assert.match(recordsSource, /annotatedUrl/)
assert.match(recordsSource, /previewUrl/)
assert.match(recordsSource, /原图/)
assert.match(recordsSource, /标注图/)

assert.doesNotMatch(imageDetectionSource, /const normalizeQualityQueueRecord =/)
assert.doesNotMatch(imageDetectionSource, /const resolveDefectPreview =/)
assert.doesNotMatch(imageDetectionSource, /const formatEvidenceConfidence =/)
assert.doesNotMatch(imageDetectionSource, /const formatPercentValue =/)

console.log('quality records contract assertions passed')
