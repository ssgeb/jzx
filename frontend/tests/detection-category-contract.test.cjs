const fs = require('fs')
const path = require('path')
const assert = require('assert')

const imageDetectionSource = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'views', 'ImageDetection.vue'),
  'utf8'
)
const homeSource = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'views', 'Home.vue'),
  'utf8'
)

for (const pair of [
  ['Normal', '正常'],
  ['Bent', '弯曲'],
  ['Deformed', '形变'],
  ['Rusty', '锈蚀'],
  ['Missing', '缺失'],
  ['Compromised', '结构损伤']
]) {
  const [code, label] = pair
  assert.match(imageDetectionSource, new RegExp(code))
  assert.match(imageDetectionSource, new RegExp(label))
}

assert.doesNotMatch(imageDetectionSource, /BSGXX:\s*'正常'/)
assert.doesNotMatch(imageDetectionSource, /BSGZX:\s*'维修'/)
assert.doesNotMatch(imageDetectionSource, /BSGGH:\s*'更换'/)

assert.match(homeSource, /结构损伤/)
assert.doesNotMatch(homeSource, /快速查看正常、维修、更换三类结果占比/)

console.log('detection category contract assertions passed')
