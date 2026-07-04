# DoorHandleCatch 项目亮点代码详解

本目录面向简历讲解和技术面试，围绕“基于 Hermes Agent 的工业视觉质检与质量追溯平台”拆解五个最有代表性的工程亮点。

## 文档导航

| 序号 | 亮点 | 核心技术 | 深度文档 |
| --- | --- | --- | --- |
| 1 | Hermes 多 Agent 编排与分层记忆 | StateGraph、Checkpoint、Mem0、RAG | [查看](01-hermes-multi-agent-memory.md) |
| 2 | 智能对话助手升级传统业务模式 | Vue、Pinia、SSE、Human-in-the-loop | [查看](02-intelligent-business-assistant.md) |
| 3 | Kafka 异步图片检测 | Kafka、OSS、Python、ONNX | [查看](03-kafka-image-detection.md) |
| 4 | 模型治理与质量追溯闭环 | ONNX Runtime、状态机、批次/工单追溯 | [查看](04-model-quality-traceability.md) |
| 5 | Nginx 与 Docker Compose 部署 | Nginx、least_conn、Docker Compose | [查看](05-nginx-docker-deployment.md) |

## 推荐阅读顺序

1. 先读 Hermes Agent，理解智能体如何决策。
2. 再读智能业务助手，理解 Agent 如何落到真实业务操作。
3. 阅读 Kafka 检测链路，掌握核心异步任务架构。
4. 阅读模型与质量追溯，理解工业业务闭环。
5. 最后阅读部署文档，理解系统如何以双实例方式运行。

## 面试使用方法

- 一面：重点记忆每篇开头的架构图、完整流程和关键设计总结。
- 二面：重点掌握代码片段、异常分支、幂等和技术取舍。
- 项目介绍：先说明业务问题，再说架构方案，最后用一处真实代码证明不是概念设计。
- 追问优化：明确区分“当前已经实现”和“后续可以升级”，避免把规划能力描述成现有能力。

