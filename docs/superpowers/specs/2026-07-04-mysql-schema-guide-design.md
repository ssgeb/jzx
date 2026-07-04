# MySQL 数据库表结构与业务关系文档设计

## 目标

新增 `docs/MySQL数据库表结构与业务关系详解.md`，以当前项目最终数据库结构为准，完整说明核心表用途、字段名、MySQL 类型、可空性、默认值、主外键、索引、状态值、表关系和业务数据流。

## 信息来源与事实优先级

1. `src/main/resources/db/schema.sql`：最终表结构的主要事实来源。
2. `migration-V2.sql` 至 `migration-V14-*.sql`：补充最终索引、约束、字段演进后的语义。
3. Java Entity、Mapper 和 Service：核对字段在业务代码中的实际用途及状态值。
4. 若 SQL 与 Java 注释存在差异，文档明确说明差异，不自行推测数据库中不存在的约束。

## 组织方式

采用按业务域分组的方案，而不是简单照建表顺序罗列。

### 用户认证域

- `users`

### Hermes Agent 会话域

- `chat_session`
- `chat_message`
- `chat_pending_action`

### 检测与质量域

- `detection_task`

### 模型治理域

- `model_management`
- `model_operation_log`

### 员工与设备域

- `employee`
- `device_management`
- `device_capture_alert`
- `device_usage_record`

共覆盖 11 张当前核心业务表。已被注释为移除的 `image_detection_data` 不作为当前表介绍，只在历史说明中指出已由 `detection_task` 替代。

## 文档结构

1. 数据库定位、MySQL/InnoDB/字符集说明。
2. 整体 ER 关系 ASCII 图。
3. 11 张核心表总览，包含业务域、主键和主要关系。
4. 按五个业务域逐表展开。
5. 每张表提供完整字段字典。
6. 主键、外键、唯一约束和索引说明。
7. 状态字段合法值和状态迁移说明。
8. LONGTEXT/JSON 字段的数据结构和使用原因。
9. 创建检测任务、Agent 对话、设备分配、模型发布等典型数据流。
10. 常用 SQL 查询示例及对应索引。
11. 当前设计取舍、风险和可优化方向。
12. MySQL/数据库面试问题与项目化答案。

## 字段字典格式

每张表使用以下统一字段表格：

| 字段名 | MySQL 类型 | 可空 | 默认值 | 键/索引 | 业务含义 |
| --- | --- | --- | --- | --- | --- |

字段信息必须来自 SQL，不把 Java 类型误写成 MySQL 类型。对 `LONGTEXT` 中保存的 JSON，需要说明它是“应用层 JSON”，而非 MySQL 原生 `JSON` 列。

## 关系与约束说明原则

- 区分“数据库真实外键”和“仅由应用代码维护的逻辑关联”。
- 标明 `ON DELETE CASCADE`、`SET NULL` 或其他实际删除行为。
- 对没有外键但经常关联的字段，明确写作逻辑关系，不声称数据库强制完整性。
- 复合索引按最左前缀规则说明能支持的查询模式。
- 不建议未经测量地新增或删除索引；优化建议必须与现有查询方式对应。

## 状态与数据流

重点解释：

- `detection_task` 的上传、派发、检测、复核、处置、返工状态。
- `chat_pending_action` 的 `PENDING → EXECUTING → COMPLETED/FAILED` 状态。
- `model_management` 的 `DRAFT/READY/PUBLISHED/ARCHIVED` 生命周期。
- `device_management` 的占用状态和在线状态。
- `device_usage_record` 与设备、员工的使用记录关系。

## 验收标准

- 11 张核心表全部出现，且没有重复或遗漏。
- 每张表包含用途、完整字段字典、约束和索引说明。
- 所有字段名和 MySQL 类型可在最终 SQL 中核对。
- ER 图能区分真实外键与逻辑关系。
- 状态值与当前 Service 代码一致。
- 文档包含典型 SQL、设计风险和面试问答。
- 不出现未完成标记或模糊占位内容。
