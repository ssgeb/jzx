const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const userStore = readFrontendFile('src', 'stores', 'user.js')
const requestApi = readFrontendFile('src', 'api', 'request.js')
const chatApi = readFrontendFile('src', 'api', 'chatAssistant.js')
const router = readFrontendFile('src', 'router', 'index.js')

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
