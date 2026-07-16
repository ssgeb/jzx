const assert = require('node:assert/strict')
const test = require('node:test')
const { readFrontendFile } = require('./helpers/project-source.cjs')

const launcherSource = readFrontendFile('src', 'components', 'chat', 'ChatAssistantLauncher.vue')
const drawerSource = readFrontendFile('src', 'components', 'chat', 'ChatAssistantDrawer.vue')
const sidebarSource = readFrontendFile('src', 'components', 'chat', 'ChatSidebar.vue')
const layoutSource = readFrontendFile('src', 'layout', 'index.vue')

test('assistant launcher clamps persisted coordinates on mount and resize', () => {
  assert.match(launcherSource, /ref="launcherRef"/)
  assert.match(launcherSource, /const clampToViewport\s*=\s*\(\)\s*=>/)
  assert.match(launcherSource, /window\.addEventListener\('resize', clampToViewport\)/)
  assert.match(launcherSource, /window\.removeEventListener\('resize', clampToViewport\)/)
  assert.match(launcherSource, /nextTick\(clampToViewport\)/)
})

test('assistant drawer recalculates its width from the current viewport', () => {
  assert.match(drawerSource, /:size="drawerSize"/)
  assert.match(drawerSource, /v-if="!isMobile"/)
  assert.match(drawerSource, /const viewportWidth = ref\(window\.innerWidth\)/)
  assert.match(drawerSource, /const isMobile = computed\(/)
  assert.match(drawerSource, /const drawerSize = computed\(/)
  assert.match(drawerSource, /const handleViewportResize\s*=\s*\(\)\s*=>/)
  assert.match(drawerSource, /window\.addEventListener\('resize', handleViewportResize\)/)
  assert.match(drawerSource, /window\.removeEventListener\('resize', handleViewportResize\)/)
  assert.doesNotMatch(drawerSource, /const MAX_WIDTH\s*=/)
})

test('assistant session sidebar defaults to compact mode on mobile', () => {
  assert.match(drawerSource, /<ChatSidebar\s+:compact="isMobile"\s*\/>/)
  assert.match(sidebarSource, /defineProps\(\{[\s\S]*compact:/)
  assert.match(sidebarSource, /const collapsed = ref\(props\.compact\)/)
  assert.match(sidebarSource, /watch\(\(\) => props\.compact/)
})

test('application shell uses fluid viewport-aware spacing', () => {
  assert.match(layoutSource, /padding:\s*clamp\(/)
  assert.match(layoutSource, /gap:\s*clamp\(/)
})
