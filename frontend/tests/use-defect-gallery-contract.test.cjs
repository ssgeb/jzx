const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const composableSource = readFrontendFile('src', 'composables', 'useDefectGallery.js')
const imageDetectionSource = readFrontendFile('src', 'views', 'ImageDetection.vue')

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
