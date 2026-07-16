const test = require('node:test')
const assert = require('node:assert/strict')
const { readFrontendFile } = require('./helpers/project-source.cjs')

test('layout identifies every signed-in account as an enterprise user', () => {
  const source = readFrontendFile('src', 'layout', 'index.vue')
  assert.match(source, /企业用户/)
  assert.doesNotMatch(source, /系统管理账号/)
})

test('user manual documents shared enterprise capabilities', () => {
  const source = readFrontendFile('src', 'views', 'UserManual.vue')
  assert.match(source, /所有已登录用户/)
  assert.match(source, /共享/)
})
