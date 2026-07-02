const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const root = path.resolve(__dirname, '../..')
const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8')
const serviceBlock = (compose, name) => {
  const marker = `  ${name}:`
  const start = compose.indexOf(marker)
  assert.notEqual(start, -1, `missing service ${name}`)
  const remainder = compose.slice(start + marker.length)
  const nextSection = remainder.search(/\n  [\w-]+:|\nvolumes:|\nnetworks:/)
  return nextSection === -1 ? remainder : remainder.slice(0, nextSection)
}

test('compose declares two private Spring Boot instances', () => {
  const compose = read('compose.nginx.yml')
  assert.match(compose, /^\s{2}backend-1:/m)
  assert.match(compose, /^\s{2}backend-2:/m)
  assert.doesNotMatch(serviceBlock(compose, 'backend-1'), /ports:/)
  assert.doesNotMatch(serviceBlock(compose, 'backend-2'), /ports:/)
  assert.match(compose, /SERVER_PORT:\s*8080/)
  assert.match(compose, /host\.docker\.internal:host-gateway/)
})

test('backend image uses Maven build and non-root Java runtime', () => {
  const dockerfile = read('deploy/backend/Dockerfile')
  assert.match(dockerfile, /FROM maven:.* AS build/)
  assert.match(dockerfile, /mvnw\.cmd|\.\/mvnw|mvn .*package/)
  assert.match(dockerfile, /FROM eclipse-temurin:17-jre/)
  assert.match(dockerfile, /USER app/)
  assert.match(dockerfile, /actuator\/health/)
})
