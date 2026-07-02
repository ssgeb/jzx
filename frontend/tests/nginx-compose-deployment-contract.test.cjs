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
  assert.match(dockerfile, /chown -R app:app \/app/)
  assert.match(dockerfile, /USER app/)
  assert.match(dockerfile, /actuator\/health/)
})

test('nginx serves Vue and balances API traffic across both backends', () => {
  const nginx = read('deploy/nginx/nginx.conf')
  assert.match(nginx, /upstream doorhandle_backend\s*{[\s\S]*least_conn;/)
  assert.match(nginx, /server backend-1:8080 max_fails=3 fail_timeout=10s;/)
  assert.match(nginx, /server backend-2:8080 max_fails=3 fail_timeout=10s;/)
  assert.match(nginx, /location \/api\/\s*{[\s\S]*proxy_pass http:\/\/doorhandle_backend;/)
  assert.match(nginx, /try_files \$uri \$uri\/ \/index\.html;/)
  assert.match(nginx, /location \/assets\/\s*{[\s\S]*immutable/)
})

test('nginx keeps assistant SSE responses unbuffered', () => {
  const nginx = read('deploy/nginx/nginx.conf')
  assert.match(nginx, /location = \/api\/chat-assistant\/messages\/stream/)
  assert.match(nginx, /proxy_buffering off;/)
  assert.match(nginx, /proxy_cache off;/)
  assert.match(nginx, /proxy_read_timeout 300s;/)
})

test('compose exposes only nginx to the host', () => {
  const compose = read('compose.nginx.yml')
  assert.match(compose, /^\s{2}nginx:/m)
  assert.match(compose, /NGINX_PORT:-80}:80/)
  assert.match(compose, /condition:\s*service_healthy/)
})

test('deployment environment example uses external services and placeholders', () => {
  const env = read('deploy/docker.env.example')
  const gitignore = read('.gitignore')
  assert.match(env, /DB_URL=jdbc:mysql:\/\/host\.docker\.internal:3306\/doorhandledb/)
  assert.match(env, /REDIS_HOST=host\.docker\.internal/)
  assert.match(env, /APP_KAFKA_BOOTSTRAP_SERVERS=host\.docker\.internal:9092/)
  assert.match(env, /JWT_SECRET=replace-with-at-least-32-random-characters/)
  assert.doesNotMatch(env, /sk-[A-Za-z0-9]{16,}/)
  assert.match(gitignore, /^deploy\/docker\.env$/m)
})

test('deployment guide documents standalone Compose fallback', () => {
  const guide = read('docs/nginx-compose-deployment.md')
  assert.match(guide, /docker-compose -f compose\.nginx\.yml/)
})
