const assert = require('assert')
const fs = require('fs')
const path = require('path')

const read = (...segments) => fs.readFileSync(path.join(__dirname, '..', ...segments), 'utf8')

const composableSource = read('src', 'composables', 'useDefectGallery.js')
const imageDetectionSource = read('src', 'views', 'ImageDetection.vue')

for (const name of [
  'defectGalleryLoading',
  'defectGalleryRecords',
  'defectGalleryTotal',
  'defectGalleryPage',
  'defectGalleryPageSize',
  'defectFilters',
  'loadDefectGallery',
  'searchDefectGallery',
  'resetDefectGalleryFilters',
  'onDefectGallerySizeChange'
]) {
  assert.match(composableSource, new RegExp(name))
  assert.match(imageDetectionSource, new RegExp(name))
}

assert.match(composableSource, /fetchDefectGallery/)
assert.match(composableSource, /buildDefectGalleryParams/)
assert.match(composableSource, /defectType/)
assert.match(composableSource, /severityLevel/)
assert.match(composableSource, /deviceName/)
assert.match(composableSource, /batchNo/)
assert.match(composableSource, /modelId/)
assert.match(imageDetectionSource, /useDefectGallery\(\)/)

assert.doesNotMatch(imageDetectionSource, /fetchDefectGallery/)
assert.doesNotMatch(imageDetectionSource, /const defectGalleryLoading = ref/)
assert.doesNotMatch(imageDetectionSource, /const defectFilters = reactive/)
assert.doesNotMatch(imageDetectionSource, /const buildDefectGalleryParams =/)
assert.doesNotMatch(imageDetectionSource, /const loadDefectGallery = async/)
assert.doesNotMatch(imageDetectionSource, /const resetDefectGalleryFilters =/)

console.log('use defect gallery contract assertions passed')
