const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const storeSource = readFrontendFile('src', 'stores', 'chatAssistant.js')
const listSource = readFrontendFile('src', 'components', 'chat', 'ChatMessageList.vue')
const cardSource = readFrontendFile('src', 'components', 'chat', 'ChatPendingActionCard.vue')

assert.match(storeSource, /confirmingActionIds/)
assert.match(storeSource, /confirmingActionIds\.value\.has\(actionId\)/)
assert.match(storeSource, /confirmingActionIds\.value\.add\(actionId\)/)
assert.match(storeSource, /confirmingActionIds\.value\.delete\(actionId\)/)

assert.match(listSource, /:busy="confirmingActionIds\.has\(item\.actionId\)"/)
assert.match(cardSource, /busy:\s*\{\s*type:\s*Boolean/)
assert.match(cardSource, /:disabled="busy"/)

console.log('chat confirm action guard contract assertions passed')
