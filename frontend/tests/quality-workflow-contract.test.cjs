const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const workflowSource = readFrontendFile('src', 'utils', 'qualityWorkflow.js')
const imageDetectionSource = readFrontendFile('src', 'views', 'ImageDetection.vue')

for (const exportName of [
  'canAssignQualityTask',
  'canReviewTask',
  'canDisposeTask',
  'canReleaseDisposition',
  'canSubmitReworkResult',
  'shouldDefaultRecheck',
  'flowStatusText',
  'reviewStatusText',
  'dispositionActionText'
]) {
  assert.match(workflowSource, new RegExp(`export const ${exportName}`))
  assert.match(imageDetectionSource, new RegExp(exportName))
}

for (const status of [
  'PENDING_REVIEW',
  'REWORK_REQUIRED',
  'RECHECK_REQUIRED',
  'RELEASED',
  'SCRAPPED',
  'ARCHIVED'
]) {
  assert.match(workflowSource, new RegExp(status))
}

assert.match(workflowSource, /TERMINAL_FLOW_STATUSES/)
assert.match(workflowSource, /DISPOSABLE_FLOW_STATUSES/)
assert.match(workflowSource, /RELEASE_ALLOWED_CONCLUSIONS/)

assert.doesNotMatch(imageDetectionSource, /const canDisposeTask =/)
assert.doesNotMatch(imageDetectionSource, /const canAssignQualityTask =/)
assert.doesNotMatch(imageDetectionSource, /const canReleaseDisposition =/)
assert.doesNotMatch(imageDetectionSource, /const flowStatusText =/)

console.log('quality workflow contract assertions passed')
