const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const projectRoot = path.resolve(__dirname, '..', '..')

const readProjectFile = (...segments) => {
  const filePath = path.join(projectRoot, ...segments)
  return fs.existsSync(filePath) ? fs.readFileSync(filePath, 'utf8') : ''
}

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
