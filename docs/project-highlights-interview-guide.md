# 基于 Hermes Agent 的工业视觉质检平台——五大项目亮点

本文是项目亮点总览，适合快速复习和简历表述。源码级流程、代码片段及面试追问见对应深度文档。

## 深度文档导航

| 亮点 | 一句话说明 | 详细讲解 |
| --- | --- | --- |
| Hermes 多 Agent 与记忆 | StateGraph 编排专业 Agent，并融合 Checkpoint、Mem0 和 RAG | [进入文档](project-highlights/01-hermes-multi-agent-memory.md) |
| 智能业务助手 | 用自然语言、SSE 和业务卡片替代菜单检索与多页面表单 | [进入文档](project-highlights/02-intelligent-business-assistant.md) |
| Kafka 图片检测 | OSS 直传、Kafka 调度、Python ONNX Worker 和幂等结果回传 | [进入文档](project-highlights/03-kafka-image-detection.md) |
| 模型与质量追溯 | 模型准入、指标治理、人工复核、处置、返工和工单追溯 | [进入文档](project-highlights/04-model-quality-traceability.md) |
| Nginx 容器部署 | 静态 Web、API 反代、双实例负载均衡和 SSE 无缓冲代理 | [进入文档](project-highlights/05-nginx-docker-deployment.md) |

## 亮点一：Hermes 多 Agent 编排与分层记忆

基于 StateGraph 注册 Router、Detection、Resource、Report、Ops、SlotFilling、HumanConfirm、Responder 和 Fallback 节点。系统在每轮对话前恢复 MySQL Checkpoint，检索 ChromaDB 系统知识和 Mem0 用户记忆，再由 Router 选择专业 Agent。写操作必须经人工确认，运行时通过次数、耗时、节点访问和重复路由守卫防止死循环。

## 亮点二：智能对话助手升级传统业务模式

助手不是简单知识问答，而是统一的自然语言业务操作层。它能够查询检测任务、设备和报告，返回结构化业务卡片；对于复核、处置等修改操作，先持久化待确认动作，再经用户确认调用真实 Java Service。前后端通过 SSE 返回状态、增量文本和最终业务对象，并按认证用户隔离会话。

## 亮点三：Kafka 驱动异步图片检测

前端创建任务后直接将图片上传 OSS，Spring Boot 发送 Created 事件，Python Worker 下载图片并执行 ONNX 推理，再上传标注图和 JSON 并发送 Finished 事件。Worker 在结果事件发送成功后才提交 Offset；Java 通过 `dispatchId` 排除旧派发结果，通过 `eventId` 实现重复事件幂等。

## 亮点四：模型治理与质量追溯闭环

ONNX 模型上传后经过格式、大小和 Runtime 加载校验，并持久化版本、验证结果、评估指标、使用统计和部署策略。检测结果进入质检分派、人工复核、缺陷处置、返工和复检状态机，同时固化模型版本、阈值、缺陷证据及操作时间线，支持任务、批次和工单追溯。

## 亮点五：Nginx 与 Docker Compose 部署

Docker Compose 启动两个相同的 Spring Boot 实例和一个 Nginx 网关。Nginx 托管 Vue 静态文件，将 API 以 `least_conn` 分配到双后端，并为 Agent SSE 关闭 buffering 和 cache。后端采用 Maven/JRE 多阶段镜像、非 root 用户、健康检查及共享上传卷，宿主机只暴露 Nginx 端口。

## 项目总体介绍话术

> 这是一个基于 Hermes Agent 的工业视觉质检与质量追溯平台。系统通过 StateGraph 编排检测、资源、报表和运维等专业 Agent，结合 MySQL Checkpoint、Mem0 长期记忆及 ChromaDB RAG 实现可恢复的多轮业务对话；图片检测采用 Kafka、OSS 和 Python ONNX Worker 异步执行，并通过 dispatchId、eventId 和手动 Offset 保证可靠性。检测结果进一步进入人工复核、缺陷处置、返工和复检闭环，部署层使用 Nginx 与 Docker Compose 实现静态资源托管、API 反向代理、双 Spring Boot 实例负载均衡和 SSE 流式代理。

