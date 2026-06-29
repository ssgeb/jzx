const fs = require('fs')
const path = require('path')
const assert = require('assert')

const storeSource = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'stores', 'chatAssistant.js'),
  'utf8'
)
const listSource = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'components', 'chat', 'ChatMessageList.vue'),
  'utf8'
)
const cardSource = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'components', 'chat', 'ChatPendingActionCard.vue'),
  'utf8'
)

assert.match(storeSource, /confirmingActionIds/)
assert.match(storeSource, /confirmingActionIds\.value\.has\(actionId\)/)
assert.match(storeSource, /confirmingActionIds\.value\.add\(actionId\)/)
assert.match(storeSource, /confirmingActionIds\.value\.delete\(actionId\)/)

assert.match(listSource, /:busy="confirmingActionIds\.has\(item\.actionId\)"/)
assert.match(cardSource, /busy:\s*\{\s*type:\s*Boolean/)
assert.match(cardSource, /:disabled="busy"/)

console.log('chat confirm action guard contract assertions passed')
