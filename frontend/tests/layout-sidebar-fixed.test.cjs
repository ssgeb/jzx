const fs = require('fs')
const path = require('path')
const assert = require('assert')

const filePath = path.join(__dirname, '..', 'src', 'layout', 'index.vue')
const source = fs.readFileSync(filePath, 'utf8')

assert.match(
  source,
  /\.layout-shell\s*\{[\s\S]*height:\s*100vh;[\s\S]*overflow:\s*hidden;/,
  'layout-shell should lock the viewport height and prevent page scrolling'
)

assert.match(
  source,
  /\.layout-sidebar\s*\{[\s\S]*position:\s*sticky;[\s\S]*top:\s*18px;[\s\S]*height:\s*calc\(100vh - 36px\);[\s\S]*overflow-y:\s*auto;/,
  'layout-sidebar should stay pinned within the viewport'
)

assert.match(
  source,
  /\.layout-content\s*\{[\s\S]*overflow-y:\s*auto;/,
  'layout-content should own vertical scrolling'
)

console.log('layout sidebar fixed assertions passed')
