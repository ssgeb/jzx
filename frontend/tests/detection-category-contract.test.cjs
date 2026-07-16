const assert = require('assert')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const imageDetectionSource = readFrontendFile('src', 'views', 'ImageDetection.vue')
const homeSource = readFrontendFile('src', 'views', 'Home.vue')
const detectionTaskStoreSource = readFrontendFile('src', 'stores', 'taskStore.js')

for (const pair of [
  ['Normal', '正常'],
  ['Bent', '弯曲'],
  ['Deformed', '形变'],
  ['Rusty', '锈蚀'],
  ['Missing', '缺失'],
  ['Compromised', '结构损伤']
]) {
  const [code, label] = pair
  assert.match(detectionTaskStoreSource, new RegExp(code))
  assert.match(detectionTaskStoreSource, new RegExp(label))
}

assert.match(imageDetectionSource, /CATEGORY_LABELS/)
assert.match(imageDetectionSource, /CATEGORY_ALIASES/)
assert.match(imageDetectionSource, /CATEGORY_COLORS/)

assert.doesNotMatch(imageDetectionSource, /BSGXX:\s*'正常'/)
assert.doesNotMatch(imageDetectionSource, /BSGZX:\s*'维修'/)
assert.doesNotMatch(imageDetectionSource, /BSGGH:\s*'更换'/)

assert.match(homeSource, /结构损伤/)
assert.doesNotMatch(homeSource, /快速查看正常、维修、更换三类结果占比/)

console.log('detection category contract assertions passed')
