# Remote Kafka Detection Architecture

**Goal:** 为当前项目确定一套稳定、可联调、可逐步落地的异步检测架构。约束条件是：`FastAPI` 检测服务部署在远程项目服务器，前端和 Spring Boot 主要在本地开发环境运行，图片文件继续存阿里云 OSS。

---

## 1. Recommended Topology

推荐采用“单 Kafka、远程检测执行、本地业务编排”的结构：

```text
本地前端
    |
    v
本地 Spring Boot  ---> 本地 MySQL
    |
    v
远程 Kafka
    |
    v
远程 Python worker（复用 FastAPI 检测代码）
    |
    v
阿里云 OSS
```

核心原则：

- 只保留一个 Kafka
- Kafka 放在远程环境
- 远程检测服务不再要求由 Java 同步 HTTP 调用
- OSS 继续作为原图、标注图、结果 JSON 的统一存储
- MySQL 当前阶段可以继续保留在本地

---

## 2. Why This Topology

相比“本地一个 Kafka、远程一个 Kafka”或“本地 Kafka 让远程服务反连”，这个方案更合适当前项目：

1. 远程检测服务本来就在远程，Kafka 放远程后，worker 消费最稳定。
2. 本地 Spring Boot 连远程 Kafka，比远程 Python 反向连本地 Kafka 更容易配置和排障。
3. 不需要做两套 Kafka 之间的消息同步、桥接或镜像。
4. 后续如果项目进入正式部署，这个结构基本可以直接沿用。

---

## 3. Component Responsibilities

### 3.1 Frontend

职责：

- 选择本地固定目录结构的图片文件夹
- 自动解析 `采集日期 / 地区 / 采集员 / 采集设备`
- 允许用户在页面上确认和修改采集信息
- 通过 Spring Boot 获取 OSS 预签名上传地址
- 直传原图到 OSS
- 轮询 Spring Boot 查询任务状态和结果

前端不直接连接 Kafka，也不直接连接远程 FastAPI。

### 3.2 Spring Boot

职责：

- 创建检测任务
- 将任务元数据写入 MySQL
- 生成 OSS 上传地址
- 确认 OSS 上传完成
- 向 Kafka 发布 `detection.task.created`
- 消费 `detection.task.finished`
- 更新任务状态、检测开始时间、检测结束时间、结果路径和结果统计
- 向前端提供任务查询和结果查询接口

Spring Boot 是这套系统里的业务编排中心。

### 3.3 Kafka

职责：

- 解耦本地 Spring Boot 与远程 Python 检测执行端
- 缓冲检测任务
- 承载任务创建事件和任务完成事件

建议 Topic：

- `detection.task.created`
- `detection.task.finished`

后续可选增加：

- `detection.task.progress`

### 3.4 Remote Python Worker

职责：

- 消费 `detection.task.created`
- 从 OSS 读取原图
- 复用现有 FastAPI/模型代码执行检测
- 将标注图上传到 OSS `results/preview/`
- 将 `detection_results.json` 上传到 OSS `results/`
- 发送 `detection.task.finished`

注意：

- 这里的“worker”可以与远程 FastAPI 项目共用一套 Python 环境和模型文件
- 不要求本地再起一套 FastAPI 服务

### 3.5 OSS

职责：

- 保存原图
- 保存标注图
- 保存检测结果 JSON

建议路径：

```text
detection/{captureDate}/{region}/{collector}/{deviceName}/{uploadTimestamp}_{taskId}/originals/{relativePath}
detection/{captureDate}/{region}/{collector}/{deviceName}/{uploadTimestamp}_{taskId}/results/preview/{relativePath}
detection/{captureDate}/{region}/{collector}/{deviceName}/{uploadTimestamp}_{taskId}/results/detection_results.json
```

### 3.6 MySQL

职责：

- 保存任务主记录
- 保存任务状态
- 保存检测开始时间、结束时间
- 保存结果 JSON OSS 路径
- 保存采集信息和任务统计信息

当前阶段继续本地 MySQL 是合理的，后面再决定是否迁到远程。

---

## 4. End-to-End Flow

### 4.1 Task Creation and Upload

1. 前端选择文件夹并解析采集信息
2. 前端调用 Spring Boot 创建任务
3. Spring Boot 写入 `detection_task`
4. Spring Boot 返回 OSS 预签名上传地址
5. 前端将原图直传 OSS
6. 前端调用“上传完成确认”接口

### 4.2 Queue and Detect

7. Spring Boot 将任务状态更新为 `UPLOADED`
8. Spring Boot 发布 `detection.task.created`
9. 远程 Python worker 消费消息
10. worker 记录实际开始检测时间
11. worker 从 OSS 下载原图并执行检测

### 4.3 Archive and Finalize

12. worker 将标注图上传到 `results/preview/`
13. worker 生成并上传 `detection_results.json`
14. worker 发布 `detection.task.finished`
15. Spring Boot 消费完成消息
16. Spring Boot 更新：
   - `status`
   - `started_at`
   - `finished_at`
   - `result_oss_prefix`
   - `result_json_oss_key`
   - `preview_image_keys_json`
   - `statistics_json`
17. 前端轮询到任务完成并展示结果

---

## 5. Task Status Model

推荐状态：

- `UPLOADING`
- `UPLOADED`
- `QUEUED`
- `DETECTING`
- `UPLOADING_RESULT`
- `COMPLETED`
- `PARTIAL_FAILED`
- `FAILED`

状态语义：

- `QUEUED`：任务已进入 Kafka，但远程 worker 尚未开始真正检测
- `DETECTING`：远程 worker 已开始处理，`started_at` 应在这个阶段确定
- `COMPLETED`：检测和结果归档均已完成
- `PARTIAL_FAILED`：任务完成，但部分图片或结果归档失败

---

## 6. Time Semantics

时间字段定义如下：

- `capture_date`
  - 来源于本地文件夹第一层业务日期
- `created_at`
  - Spring Boot 创建任务时间
- `started_at`
  - 远程 Python worker 实际开始检测时间
- `finished_at`
  - 远程 Python worker 完成检测并生成完成事件后，由 Spring Boot 落库

这样可以严格区分：

- 采集时间
- 任务创建时间
- 检测开始时间
- 检测结束时间

---

## 7. Deployment Recommendation

### 7.1 Local Side

建议本地运行：

- 前端
- Spring Boot
- MySQL

本地需要能访问：

- 远程 Kafka
- 阿里云 OSS

### 7.2 Remote Side

建议远程运行：

- Kafka
- Python worker
- 现有模型文件和检测依赖

可选：

- 保留 FastAPI HTTP 服务，作为补充调试接口
- 或仅保留 worker，不再对 Java 提供同步检测接口

---

## 8. Network Requirements

最基本要求：

- 本地 Spring Boot 能访问远程 Kafka 地址和端口
- 远程 Python worker 能访问 OSS
- 本地 Spring Boot 能访问 OSS

如果 Kafka 放远程，至少要正确配置：

- `listeners`
- `advertised.listeners`

否则本地 Java 可能能连上 Broker，但拿不到正确的返回地址。

---

## 9. Current Recommendation

当前项目最推荐的落地方式是：

1. 本地继续保留 MySQL
2. 远程部署单 Kafka
3. 远程部署 Python worker
4. 本地 Spring Boot 接远程 Kafka
5. 保留现有 OSS 链路

这是最适合当前项目阶段的折中方案：

- 开发联调成本低
- 不引入双 Kafka 复杂度
- 后续可平滑演进到完整远程部署

---

## 10. Next Step

这份框架确认后，下一步建议写一份更细的部署设计文档，内容包括：

- 远程 Kafka 部署方式
- Spring Boot 的远程 Kafka 配置
- Python worker 的启动方式和环境变量
- 联调顺序
- 故障排查点

