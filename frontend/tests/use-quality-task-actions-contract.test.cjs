const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const composableSource = readFrontendFile('src', 'composables', 'useQualityTaskActions.js')
const qualityPages = [
  'QualityQueue.vue',
  'DefectEvidenceGallery.vue',
  'WorkOrderTrace.vue',
  'BatchTrace.vue'
]

for (const name of [
  'reviewDialogVisible',
  'reviewSubmitting',
  'reviewTask',
  'reviewForm',
  'assignmentDialogVisible',
  'assignmentSubmitting',
  'assignmentTask',
  'assignmentForm',
  'dispositionDialogVisible',
  'dispositionSubmitting',
  'dispositionTask',
  'dispositionForm',
  'reworkDialogVisible',
  'reworkSubmitting',
  'reworkTask',
  'reworkForm',
  'applyTaskProgressUpdate',
  'openReviewDialog',
  'submitTaskReview',
  'openAssignmentDialog',
  'submitQualityAssignment',
  'openDispositionDialog',
  'submitTaskDisposition',
  'openReworkDialog',
  'submitTaskRework'
]) {
  assert.match(composableSource, new RegExp(name))
}

assert.match(composableSource, /assignDetectionQualityTask/)
assert.match(composableSource, /reviewDetectionTask/)
assert.match(composableSource, /disposeDetectionTask/)
assert.match(composableSource, /submitDetectionReworkResult/)
assert.match(composableSource, /shouldDefaultRecheck/)

for (const page of qualityPages) {
  const source = readFrontendFile('src', 'views', 'quality', page)
  assert.match(source, /useQualityTaskActions\(\{/)
  assert.match(source, /refreshQualityQueue: refreshQualityQueueIfVisible/)
  assert.match(source, /refreshDefectGallery: refreshDefectGalleryIfVisible/)
  assert.doesNotMatch(source, /assignDetectionQualityTask/)
  assert.doesNotMatch(source, /reviewDetectionTask/)
  assert.doesNotMatch(source, /disposeDetectionTask/)
  assert.doesNotMatch(source, /submitDetectionReworkResult/)
  assert.doesNotMatch(source, /const reviewDialogVisible = ref/)
  assert.doesNotMatch(source, /const reviewForm = reactive/)
  assert.doesNotMatch(source, /const applyTaskProgressUpdate = async/)
  assert.doesNotMatch(source, /const openReviewDialog = /)
  assert.doesNotMatch(source, /const submitTaskReview = async/)
  assert.doesNotMatch(source, /const submitQualityAssignment = async/)
  assert.doesNotMatch(source, /const submitTaskDisposition = async/)
  assert.doesNotMatch(source, /const submitTaskRework = async/)
}

console.log('use quality task actions contract assertions passed')
