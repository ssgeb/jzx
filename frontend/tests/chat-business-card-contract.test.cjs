const fs = require('fs')
const path = require('path')
const assert = require('assert')

const source = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'components', 'chat', 'ChatBusinessCard.vue'),
  'utf8'
)

assert.match(source, /payload\.value\.sources/)
assert.match(source, /source-strip/)
assert.match(source, /来源/)

console.log('chat business card source contract assertions passed')
