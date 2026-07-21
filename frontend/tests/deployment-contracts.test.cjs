const assert = require('node:assert/strict')
const test = require('node:test')
const { readFrontendFile, readProjectFile } = require('./helpers/project-source.cjs')

{
  const serviceBlock = (compose, name) => {
    const marker = `  ${name}:`
    const start = compose.indexOf(marker)
    assert.notEqual(start, -1, `missing service ${name}`)
    const remainder = compose.slice(start + marker.length)
    const nextSection = remainder.search(/\n  [\w-]+:|\nvolumes:|\nnetworks:/)
    return nextSection === -1 ? remainder : remainder.slice(0, nextSection)
  }

  test('compose declares two private Spring Boot instances', () => {
    const compose = readProjectFile('compose.nginx.yml')
    assert.match(compose, /^\s{2}backend-1:/m)
    assert.match(compose, /^\s{2}backend-2:/m)
    assert.doesNotMatch(serviceBlock(compose, 'backend-1'), /ports:/)
    assert.doesNotMatch(serviceBlock(compose, 'backend-2'), /ports:/)
    assert.match(compose, /SERVER_PORT:\s*8080/)
    assert.match(compose, /host\.docker\.internal:host-gateway/)
  })

  test('backend image uses Maven build and non-root Java runtime', () => {
    const dockerfile = readProjectFile('deploy', 'backend', 'Dockerfile')
    assert.match(dockerfile, /FROM maven:.* AS build/)
    assert.match(dockerfile, /mvnw\.cmd|\.\/mvnw|mvn .*package/)
    assert.match(dockerfile, /FROM eclipse-temurin:17-jre/)
    assert.match(dockerfile, /chown -R app:app \/app/)
    assert.match(dockerfile, /USER app/)
    assert.match(dockerfile, /actuator\/health/)
  })

  test('nginx serves Vue and balances API traffic across both backends', () => {
    const nginx = readProjectFile('deploy', 'nginx', 'nginx.conf')
    assert.match(nginx, /upstream doorhandle_backend\s*{[\s\S]*least_conn;/)
    assert.match(nginx, /server backend-1:8080 max_fails=3 fail_timeout=10s;/)
    assert.match(nginx, /server backend-2:8080 max_fails=3 fail_timeout=10s;/)
    assert.match(nginx, /location \/api\/\s*{[\s\S]*proxy_pass http:\/\/doorhandle_backend;/)
    assert.match(nginx, /try_files \$uri \$uri\/ \/index\.html;/)
    assert.match(nginx, /location \/assets\/\s*{[\s\S]*immutable/)
  })

  test('nginx keeps assistant SSE responses unbuffered', () => {
    const nginx = readProjectFile('deploy', 'nginx', 'nginx.conf')
    assert.match(nginx, /location = \/api\/chat-assistant\/messages\/stream/)
    assert.match(nginx, /proxy_buffering off;/)
    assert.match(nginx, /proxy_cache off;/)
    assert.match(nginx, /proxy_read_timeout 300s;/)
  })

  test('compose exposes only nginx to the host', () => {
    const compose = readProjectFile('compose.nginx.yml')
    assert.match(compose, /^\s{2}nginx:/m)
    assert.match(compose, /NGINX_PORT:-80}:80/)
    assert.match(compose, /condition:\s*service_healthy/)
    assert.doesNotMatch(serviceBlock(compose, 'python-assistant'), /ports:/)
    assert.doesNotMatch(serviceBlock(compose, 'backend-1'), /ports:/)
    assert.doesNotMatch(serviceBlock(compose, 'backend-2'), /ports:/)
  })

  test('compose runs the Python Harness Agent with an internal Java tool callback', () => {
    const compose = readProjectFile('compose.nginx.yml')
    const dockerfile = readProjectFile('python_assistant_service', 'Dockerfile')
    const nginx = readProjectFile('deploy', 'nginx', 'nginx.conf')

    assert.match(compose, /^\s{2}python-assistant:/m)
    assert.match(compose, /dockerfile:\s*python_assistant_service\/Dockerfile/)
    assert.match(compose, /JAVA_AGENT_TOOL_BASE_URL:\s*http:\/\/nginx:8081/)
    assert.match(serviceBlock(compose, 'python-assistant'), /"8090"/)
    assert.match(dockerfile, /FROM python:3\.11-slim/)
    assert.match(dockerfile, /internal\/v1\/health/)
    assert.match(nginx, /listen 8081;/)
    assert.match(nginx, /location \/internal\/v1\/agent-tools\//)
    assert.match(nginx, /proxy_pass http:\/\/doorhandle_backend;/)
  })

  test('deployment environment example uses external services and placeholders', () => {
    const env = readProjectFile('deploy', 'docker.env.example')
    const gitignore = readProjectFile('.gitignore')
    assert.match(env, /DB_URL=jdbc:mysql:\/\/host\.docker\.internal:3306\/doorhandledb/)
    assert.match(env, /REDIS_HOST=host\.docker\.internal/)
    assert.match(env, /APP_KAFKA_BOOTSTRAP_SERVERS=host\.docker\.internal:9092/)
    assert.match(env, /JWT_SECRET=replace-with-at-least-32-random-characters/)
    assert.match(env, /CHAT_ASSISTANT_ENGINE=python/)
    assert.match(env, /PYTHON_ASSISTANT_URL=http:\/\/python-assistant:8090/)
    assert.match(env, /ASSISTANT_HMAC_SECRET=replace-with-at-least-32-random-characters/)
    assert.match(env, /ASSISTANT_DEEP_AGENT_MODEL=deepseek-chat/)
    assert.doesNotMatch(env, /sk-[A-Za-z0-9]{16,}/)
    assert.match(gitignore, /^deploy\/docker\.env$/m)
  })

  test('deployment guide documents standalone Compose fallback', () => {
    const guide = readProjectFile('docs', 'nginx-compose-deployment.md')
    assert.match(guide, /docker-compose -f compose\.nginx\.yml/)
  })
}

{
  test('declares leetcode as the repository Python environment', () => {
    const environment = readProjectFile('environment.yml')

    assert.match(environment, /^name:\s*leetcode\s*$/m)
    assert.match(environment, /python=3\.10/)
    assert.match(environment, /playwright==1\.60\.0/)
    assert.match(environment, /pytest>=8\.0\.0/)
    assert.match(environment, /requests>=2\.31\.0/)
    assert.match(environment, /Pillow>=10\.0\.0/)
  })

  test('runs Python through the leetcode Conda environment without fallback', () => {
    const runner = readProjectFile('scripts', 'run-python.ps1')

    assert.match(runner, /conda env list --json/)
    assert.match(runner, /conda run --no-capture-output -n \$EnvironmentName python/)
    assert.match(runner, /EnvironmentName\s*=\s*'leetcode'/)
    assert.doesNotMatch(runner, /fallback|base environment/i)
  })

  test('configures VS Code and the batch test launcher for leetcode', () => {
    const settings = JSON.parse(readProjectFile('.vscode', 'settings.json'))
    const batchLauncher = readProjectFile('tests_python', 'run_test.bat')

    assert.match(settings['python.defaultInterpreterPath'], /envs[\\/]leetcode[\\/]python\.exe$/i)
    assert.match(batchLauncher, /conda run --no-capture-output -n leetcode python/i)
    assert.doesNotMatch(batchLauncher, /[A-Z]:\\.*anaconda.*python\.exe/i)
  })

  test('lets Playwright resolve its installed Chromium browser', () => {
    const browserSmokeTest = readProjectFile('frontend', 'tests', 'test_browser.py')
    const frontendTest = readProjectFile('frontend', 'tests', 'test_frontend.py')

    assert.doesNotMatch(browserSmokeTest, /executable_path/)
    assert.doesNotMatch(frontendTest, /executable_path/)
    assert.match(browserSmokeTest, /chromium\.launch\(headless=True\)/)
    assert.match(frontendTest, /chromium\.launch\(headless=True\)/)
  })

  test('documents a non-destructive update for the shared leetcode environment', () => {
    const readme = readProjectFile('README.md')

    assert.match(readme, /conda env update -n leetcode -f environment\.yml/)
    assert.doesNotMatch(readme, /conda env update[^\r\n]*--prune/)
  })
}

test('tests\\layout-sidebar-fixed.test.cjs', () => {
  const source = readFrontendFile('src', 'layout', 'index.vue')

  assert.match(
    source,
    /\.layout-shell\s*\{[\s\S]*height:\s*100vh;[\s\S]*overflow:\s*hidden;/,
    'layout-shell should lock the viewport height and prevent page scrolling'
  )

  assert.match(
    source,
    /\.layout-sidebar\s*\{[\s\S]*position:\s*sticky;[\s\S]*top:\s*18px;[\s\S]*height:\s*calc\(100vh - 36px\);[\s\S]*overflow-y:\s*auto;/,
    'layout-sidebar should stay pinned within the viewport'
  )

  assert.match(
    source,
    /\.layout-content\s*\{[\s\S]*overflow-y:\s*auto;/,
    'layout-content should own vertical scrolling'
  )

  console.log('layout sidebar fixed assertions passed')
})
