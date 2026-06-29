const assert = require('assert')
const fs = require('fs')
const path = require('path')

const readFrontend = (...segments) => fs.readFileSync(path.join(__dirname, '..', ...segments), 'utf8')
const readRepo = (...segments) => fs.readFileSync(path.join(__dirname, '..', '..', ...segments), 'utf8')

const presetSource = readFrontend('src', 'config', 'businessTracePresets.js')
const traceComposableSource = readFrontend('src', 'composables', 'useTraceReports.js')
const businessSeedEmptyHintSource = readFrontend('src', 'components', 'BusinessSeedEmptyHint.vue')
const userManualSource = readFrontend('src', 'views', 'UserManual.vue')
const dbReadmeSource = readRepo('src', 'main', 'resources', 'db', 'README.md')
const userGuideSource = readRepo('docs', 'system-user-guide.md')
const businessSeedStartScript = readRepo('scripts', 'start-backend-with-business-seed.ps1')
const businessSeedSources = [
  readRepo('src', 'main', 'resources', 'db', 'business-seed-new-features.sql'),
  readRepo('src', 'main', 'resources', 'db', 'business-seed-more-features.sql'),
  readRepo('src', 'main', 'resources', 'db', 'business-seed-trace-rich.sql')
]
const normalizedPages = [
  readFrontend('src', 'views', 'ImageDetection.vue'),
  readFrontend('src', 'views', 'quality', 'QualityQueue.vue'),
  readFrontend('src', 'views', 'quality', 'DefectEvidenceGallery.vue'),
  readFrontend('src', 'views', 'quality', 'WorkOrderTrace.vue'),
  readFrontend('src', 'views', 'quality', 'BatchTrace.vue'),
  readFrontend('src', 'views', 'inspection', 'InspectionHistory.vue'),
  readFrontend('src', 'views', 'inspection', 'InspectionWorkbench.vue')
]
const migrationSource = readRepo('src', 'main', 'resources', 'db', 'migration-V13-business-seed-data-normalization.sql')

assert.match(presetSource, /businessWorkOrderNos/)
assert.match(presetSource, /businessBatchNos/)
assert.match(presetSource, /WO-SH-A-001/)
assert.match(presetSource, /BATCH-SH-A-20260615-001/)
assert.doesNotMatch(presetSource, /DEMO|demo|示例|演示/)

assert.match(traceComposableSource, /businessWorkOrderNos/)
assert.match(traceComposableSource, /businessBatchNos/)
assert.doesNotMatch(traceComposableSource, /demoWorkOrderNos|demoBatchNos|loadDemoWorkOrderTrace|loadDemoBatchTrace/)

for (const source of normalizedPages) {
  assert.doesNotMatch(source, /demoWorkOrderNos|demoBatchNos|useDemoWorkOrder|useDemoBatch/)
  assert.doesNotMatch(source, /loadDemoWorkOrderTrace|loadDemoBatchTrace/)
  assert.doesNotMatch(source, /示例工单|示例批次|上方示例/)
}

for (const source of businessSeedSources) {
  assert.doesNotMatch(source, /WO-DEMO-|ALERT-DEMO-|demo_trace_|demo_task_ext_|demo\/device|demo\/evidence|demo\/tasks|demo\/trace/)
  assert.doesNotMatch(source, /演示数据：|扩展演示：|追溯演示：/)
}

assert.match(dbReadmeSource, /业务预置数据/)
assert.match(dbReadmeSource, /APP_BUSINESS_SEED_ENABLED=true/)
assert.match(dbReadmeSource, /start-backend-with-business-seed\.ps1/)
assert.doesNotMatch(userGuideSource, /初始化演示数据/)
assert.match(businessSeedEmptyHintSource, /APP_BUSINESS_SEED_ENABLED=true/)
assert.match(businessSeedEmptyHintSource, /业务预置数据导入/)
assert.match(businessSeedEmptyHintSource, /to="\/manual"/)
assert.match(userManualSource, /首次验收没有工单、批次、质检数据怎么办/)
assert.match(userManualSource, /APP_BUSINESS_SEED_ENABLED/)
assert.match(userManualSource, /mvnw\.cmd spring-boot:run/)
assert.match(userManualSource, /start-backend-with-business-seed\.ps1/)
assert.match(businessSeedStartScript, /APP_BUSINESS_SEED_ENABLED\s*=\s*'true'/)
assert.match(businessSeedStartScript, /mvnw\.cmd spring-boot:run/)

assert.match(migrationSource, /REPLACE\(`work_order_no`, 'WO-DEMO-', 'WO-'\)/)
assert.match(migrationSource, /ALERT-BIZ-/)
assert.match(migrationSource, /演示数据：/)
assert.match(migrationSource, /扩展演示：/)
assert.match(migrationSource, /追溯演示：/)

console.log('business trace presets contract assertions passed')
