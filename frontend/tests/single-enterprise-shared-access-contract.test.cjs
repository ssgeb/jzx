const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '..')

test('layout identifies every signed-in account as an enterprise user', () => {
  const source = fs.readFileSync(path.join(root, 'src/layout/index.vue'), 'utf8')
  assert.match(source, /企业用户/)
  assert.doesNotMatch(source, /系统管理账号/)
})

test('user manual documents shared enterprise capabilities', () => {
  const source = fs.readFileSync(path.join(root, 'src/views/UserManual.vue'), 'utf8')
  assert.match(source, /所有已登录用户/)
  assert.match(source, /共享/)
})
