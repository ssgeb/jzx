const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const layoutSource = readFrontendFile('src', 'layout', 'index.vue')
const userManualSource = readFrontendFile('src', 'views', 'UserManual.vue')

for (const source of [layoutSource, userManualSource]) {
  assert.doesNotMatch(source, /href="#(?!\/)/, 'Hash router pages must not use bare hash anchors')
  assert.doesNotMatch(source, /:href="`#/, 'Hash router pages must not generate bare hash anchors')
}

assert.match(userManualSource, /scrollToManualSection/)
assert.match(layoutSource, /skipToMainContent/)

console.log('hash route anchor contract assertions passed')
