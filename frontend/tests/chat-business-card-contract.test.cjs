const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const source = readFrontendFile('src', 'components', 'chat', 'ChatBusinessCard.vue')

assert.match(source, /payload\.value\.sources/)
assert.match(source, /source-strip/)
assert.match(source, /来源/)

console.log('chat business card source contract assertions passed')
