const assert = require('assert')
const fs = require('fs')
const path = require('path')

const readFrontend = (...segments) => fs.readFileSync(path.join(__dirname, '..', ...segments), 'utf8')

const layoutSource = readFrontend('src', 'layout', 'index.vue')
const userManualSource = readFrontend('src', 'views', 'UserManual.vue')

for (const source of [layoutSource, userManualSource]) {
  assert.doesNotMatch(source, /href="#(?!\/)/, 'Hash router pages must not use bare hash anchors')
  assert.doesNotMatch(source, /:href="`#/, 'Hash router pages must not generate bare hash anchors')
}

assert.match(userManualSource, /scrollToManualSection/)
assert.match(layoutSource, /skipToMainContent/)

console.log('hash route anchor contract assertions passed')
