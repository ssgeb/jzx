# 项目亮点文档拆分设计

## 目标

将现有 `docs/project-highlights-interview-guide.md` 中的五个项目亮点拆分为五份可以独立阅读、独立用于面试准备的 Markdown 深度文档，并增加一份统一索引文档。

## 输出结构

```text
docs/project-highlights/
├── README.md
├── 01-hermes-multi-agent-memory.md
├── 02-intelligent-business-assistant.md
├── 03-kafka-image-detection.md
├── 04-model-quality-traceability.md
└── 05-nginx-docker-deployment.md
```

原有总文档继续保留，定位为五大亮点概览；新目录中的文档负责深入解释。

## 单篇文档统一结构

每篇文档均采用以下结构：

1. 亮点简介与简历写法。
2. 业务背景和要解决的问题。
3. 总体架构与核心组件职责。
4. 端到端详细执行流程。
5. 关键数据、状态或消息结构。
6. 3 至 6 个项目真实核心代码片段。
7. 对代码片段进行逐行或逐语句解释。
8. 异常处理、可靠性、权限或安全设计。
9. 当前实现的技术取舍和可继续优化方向。
10. 面试高频问题及项目化参考答案。

## 代码讲解标准

- 代码必须来自当前项目，不能编造不存在的类或方法。
- 每个片段只保留与当前知识点相关的关键语句，避免整类复制。
- 代码片段之后必须解释输入、状态变化、依赖调用、输出和异常分支。
- 标注对应源码相对路径和核心方法名，方便回到项目中定位。
- 对代码事实和可选优化进行明确区分，不能把规划中的能力写成已经实现。

## 五篇文档边界

### 1. Hermes 多 Agent 与记忆

聚焦 StateGraph、Router、专业 Agent、Slot Filling、Human Confirmation、MySQL Checkpoint、Mem0、RAG 和运行守卫。不重复展开前端聊天界面和 Kafka 推理内部逻辑。

### 2. 智能业务助手

聚焦自然语言替代传统菜单操作、前后端消息链路、SSE、结构化业务卡片、语音入口、会话管理、权限隔离和真实业务 Service 调用。仅引用 Agent 编排结果，不重复解释 StateGraph 内部实现。

### 3. Kafka 图片检测

聚焦 OSS 预签名上传、任务状态机、Kafka Created/Finished 事件、Python Worker、ONNX 推理、手动 Offset、`dispatchId` 和 `eventId` 幂等，以及单图/批量任务结果处理。

### 4. 模型治理与质量追溯

聚焦 ONNX 模型上传校验、模型状态与评估指标、部署策略、检测结果、缺陷证据、人工复核、处置、返工、复检、批次和工单追溯。

### 5. Nginx 与 Docker Compose

聚焦多阶段镜像、Vue 静态资源、API 反向代理、双 Spring Boot 实例、`least_conn`、SSE 专用代理、健康检查、非 root 用户、共享卷和外部基础服务连接。

## 索引文档

`README.md` 提供：

- 项目一句话介绍。
- 五个亮点的简短摘要。
- 五篇文档的相对链接。
- 推荐阅读顺序。
- 面试准备使用建议。

## 验收标准

- 目录中存在 1 份索引和 5 份亮点文档。
- 每份亮点文档均包含详细流程、真实代码片段和逐段解释。
- 五份文档内容边界明确，不大段重复。
- 所有源码路径、类名和方法名与当前项目一致。
- Markdown 代码围栏、标题层级、表格及相对链接格式正确。
- 不出现 `TODO`、`TBD` 或未完成占位内容。
