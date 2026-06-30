const assert = require('assert')
const fs = require('fs')
const path = require('path')

const source = fs.readFileSync(path.join(__dirname, '..', 'src', 'stores', 'user.js'), 'utf8')

assert.match(source, /error\.response\?\.status === 401/)
assert.doesNotMatch(source, /\[401,\s*403\]\.includes/)

const interceptor = source.match(/request\.interceptors\.response\.use\([\s\S]*?return Promise\.reject\(error\)[\s\S]*?\n\s*\)/)?.[0] || ''
assert.match(interceptor, /clearSession\(\)/)
assert.doesNotMatch(interceptor, /logout\(\)/)

console.log('auth response guard contract assertions passed')
