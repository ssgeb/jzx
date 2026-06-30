const fs = require('fs')
const path = require('path')
const assert = require('assert')

const source = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'components', 'chat', 'ChatMessageList.vue'),
  'utf8'
)

assert.match(source, /SOURCE_PATTERN/)
assert.match(source, /messageSourceText/)
assert.match(source, /messageBodyContent/)
assert.match(source, /msg-source-strip/)
assert.match(source, /msg-source-chip/)

console.log('chat text source contract assertions passed')
