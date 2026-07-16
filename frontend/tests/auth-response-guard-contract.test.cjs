const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const source = readFrontendFile('src', 'stores', 'user.js')

assert.match(source, /error\.response\?\.status === 401/)
assert.doesNotMatch(source, /\[401,\s*403\]\.includes/)

const interceptor = source.match(/request\.interceptors\.response\.use\([\s\S]*?return Promise\.reject\(error\)[\s\S]*?\n\s*\)/)?.[0] || ''
assert.match(interceptor, /clearSession\(\)/)
assert.doesNotMatch(interceptor, /logout\(\)/)

console.log('auth response guard contract assertions passed')
