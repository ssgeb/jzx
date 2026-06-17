# MySQL 索引与连接池优化说明

## 目标

优化检测任务、聊天会话、设备使用记录、模型管理等高频查询的 MySQL/InnoDB 访问路径，并补充可配置的 Hikari 连接池参数。

## 索引策略

- 检测任务列表按 `created_at DESC` 排序，并常按 `status`、`collector`、`device_name`、`region`、`flow_status` 过滤，因此新增对应的过滤 + 排序复合索引。
- 聊天会话按 `username + status` 过滤，并按 `updated_at`、`pinned + updated_at` 排序，因此新增用户会话复合索引。
- 设备使用记录常按 `device_id`、`employee_id`、`status` 过滤，并按 `start_time DESC` 排序，因此新增对应复合索引。
- 模型操作日志按 `model_id` 查询并按 `operation_time DESC, id DESC` 排序，因此新增复合索引。
- 设备与人员统计常按状态/类型聚合或过滤，因此补充状态和类型组合索引。

## 迁移

新增迁移脚本：

- `src/main/resources/db/migration-V7-index-and-pool.sql`

脚本通过 `INFORMATION_SCHEMA.STATISTICS` 检查索引是否存在，再执行 `CREATE INDEX`，可重复执行。

## 连接池

`application.yml` 新增 Hikari 配置，默认值较保守：

- `DB_POOL_MAX_SIZE=20`
- `DB_POOL_MIN_IDLE=5`
- `DB_POOL_CONNECTION_TIMEOUT_MS=30000`
- `DB_POOL_VALIDATION_TIMEOUT_MS=5000`
- `DB_POOL_IDLE_TIMEOUT_MS=600000`
- `DB_POOL_MAX_LIFETIME_MS=1800000`

生产环境应结合 MySQL `max_connections`、应用实例数和实际并发进行调整。

## 验证建议

上线前在测试库执行：

```sql
EXPLAIN SELECT * FROM detection_task WHERE status = 'COMPLETED' ORDER BY created_at DESC LIMIT 20;
EXPLAIN SELECT * FROM chat_session WHERE username = 'admin' AND status = 'ACTIVE' ORDER BY updated_at DESC LIMIT 1;
EXPLAIN SELECT * FROM device_usage_record WHERE device_id = 1 ORDER BY start_time DESC LIMIT 20;
EXPLAIN SELECT id, model_id, operation_type, operator, operation_time, remark FROM model_operation_log WHERE model_id = 1 ORDER BY operation_time DESC, id DESC;
```

重点观察是否避免 `type: ALL`、`Using filesort`、`Using temporary`。

## 回滚

本轮只新增索引和连接池配置。若索引带来写入压力，可在低峰期按索引名逐个 `DROP INDEX ... ON ...` 回退；连接池配置可通过环境变量调小或恢复默认值。
