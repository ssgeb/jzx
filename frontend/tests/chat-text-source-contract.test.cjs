const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const source = readFrontendFile('src', 'components', 'chat', 'ChatMessageList.vue')

assert.match(source, /SOURCE_PATTERN/)
assert.match(source, /messageSourceText/)
assert.match(source, /messageBodyContent/)
assert.match(source, /msg-source-strip/)
assert.match(source, /msg-source-chip/)

console.log('chat text source contract assertions passed')
