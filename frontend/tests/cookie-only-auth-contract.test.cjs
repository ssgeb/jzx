const assert = require('assert')
const fs = require('fs')
const path = require('path')

const read = (...segments) => fs.readFileSync(path.join(__dirname, '..', ...segments), 'utf8')

const userStore = read('src', 'stores', 'user.js')
const requestApi = read('src', 'api', 'request.js')
const chatApi = read('src', 'api', 'chatAssistant.js')
const router = read('src', 'router', 'index.js')

assert.doesNotMatch(userStore, /localStorage\.setItem\(['"]token['"]/)
assert.doesNotMatch(userStore, /localStorage\.getItem\(['"]token['"]/)
assert.doesNotMatch(userStore, /defaults\.headers\.common\[['"]Authorization['"]\]/)
assert.match(userStore, /hasCheckedAuth/)

assert.doesNotMatch(requestApi, /Authorization\s*=/)
assert.doesNotMatch(requestApi, /localStorage\.getItem\(['"]token['"]/)
assert.match(requestApi, /withCredentials\s*=\s*true/)

assert.doesNotMatch(chatApi, /localStorage\.getItem\(['"]token['"]/)
assert.doesNotMatch(chatApi, /Authorization:\s*`Bearer/)
assert.match(chatApi, /credentials:\s*['"]include['"]/)

assert.match(router, /await userStore\.checkAuth\(\)/)
assert.doesNotMatch(router, /!userStore\.token/)

console.log('cookie-only auth contract assertions passed')
