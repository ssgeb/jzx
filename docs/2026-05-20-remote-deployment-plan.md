# Remote Deployment Plan

**Goal:** 确定这套项目的唯一远程部署方案。远程当前只有一个现有的 FastAPI 检测项目，主入口是 `main.py`。本方案不再保留“可选”“建议”“后续可考虑”这类模糊口径，只给最终执行方案。

---

## 1. Final Architecture

最终架构固定为：

```text
本地前端
    |
    v
本地 Spring Boot  ---> 本地 MySQL
    |
    v
远程 Kafka（单实例）
    |
    v
远程 Python worker（复用远程 main.py 检测逻辑）
    |
    v
阿里云 OSS
```

固定原则：

1. 只使用一个 Kafka。
2. Kafka 部署在远程服务器。
3. 远程检测执行端不再由 Spring Boot 同步调用 HTTP 接口。
4. 远程检测执行端固定为 `kafka_detection_worker.py`。
5. 远程 `main.py` 只作为检测逻辑提供方，被 worker 导入复用。
6. Spring Boot 只负责创建任务、发 Kafka、收结果、写 MySQL。

---

## 2. Remote Server Scope

远程服务器只承担这两类服务：

1. Kafka
2. Python 检测 worker

远程服务器上的现有 `main.py` 不作为 Java 调用的 Web 接口使用。

也就是说，最终运行形态不是：

- Java -> HTTP -> FastAPI

而是：

- Java -> Kafka -> Python worker

---

## 3. Remote Project Structure

远程现有检测项目目录最终固定包含这些核心文件：

```text
remote-detection-project/
├── main.py
├── kafka_detection_worker.py
├── kafka_event_models.py
├── kafka_settings.py
├── oss_result_uploader.py
├── requirements-kafka.txt
├── plotting.py
└── 模型文件 / 推理依赖
```

说明：

- `main.py` 保留现有检测逻辑
- `kafka_detection_worker.py` 是远程唯一检测执行入口
- worker 通过 `import main` 复用模型加载、图片推理、标注生成逻辑

---

## 4. Final Runtime Mode

远程最终运行的不是 FastAPI HTTP 服务，而是独立 Python worker 进程。

固定执行方式：

```text
python kafka_detection_worker.py
```

`main.py` 不单独启动 `uvicorn`，不对本地 Spring Boot 暴露检测 HTTP 接口。

原因已经定死：

1. 当前项目已经切到 Kafka 异步链路。
2. 再保留 Java -> FastAPI HTTP 主链路会造成双链路并存，增加混乱。
3. 远程只有一个现有 FastAPI 项目代码，最正确的做法是复用它的检测逻辑，而不是继续把它当成 Spring Boot 的远程 RPC 服务。

---

## 5. Kafka Final Plan

### 5.1 Deployment Position

Kafka 固定部署在远程服务器。

### 5.2 Kafka Form

Kafka 固定为单实例部署，作为当前项目唯一消息队列。

### 5.3 Kafka Topics

只使用两个 Topic：

- `detection.task.created`
- `detection.task.finished`

当前阶段不引入第三个 Topic，不部署 `detection.task.progress`。

### 5.4 Kafka Responsibility

Kafka 只承担两件事：

1. Spring Boot 发布检测创建事件
2. Python worker 发布检测完成事件

---

## 6. OSS Final Plan

OSS 固定为唯一文件存储层。

### 6.1 Original Images

原图由前端通过 Spring Boot 返回的预签名地址直传 OSS。

### 6.2 Detection Reads

远程 worker 从 OSS 读取原图，不经过 Java 转发文件内容。

### 6.3 Detection Writes

远程 worker 把结果固定写到：

```text
detection/{captureDate}/{region}/{collector}/{deviceName}/{uploadTimestamp}_{taskId}/results/
```

其中必须包含：

- `preview/{relativePath}`
- `detection_results.json`

原图路径固定为：

```text
detection/{captureDate}/{region}/{collector}/{deviceName}/{uploadTimestamp}_{taskId}/originals/{relativePath}
```

---

## 7. MySQL Final Plan

MySQL 当前阶段固定继续使用本地 MySQL。

本地 MySQL 负责保存：

- `task_id`
- `status`
- `stage`
- `capture_date`
- `region`
- `collector`
- `device_name`
- `source_oss_prefix`
- `result_oss_prefix`
- `result_json_oss_key`
- `started_at`
- `finished_at`
- `statistics_json`
- `preview_image_keys_json`

远程服务器当前不部署 MySQL。

---

## 8. Time Semantics

时间口径固定如下：

- `capture_date`
  - 来自本地目录第一层业务日期
- `created_at`
  - Spring Boot 创建任务时间
- `started_at`
  - 远程 worker 实际开始处理任务时间
- `finished_at`
  - 远程 worker 完成检测并生成完成事件后，由 Spring Boot 落库

这里没有第二套解释，不允许再把 `started_at` 理解成“上传完成时间”或“Java 发消息时间”。

---

## 9. Final End-to-End Flow

唯一有效的任务链路如下：

1. 前端选择文件夹
2. 前端解析并确认 `采集日期 / 地区 / 采集员 / 采集设备`
3. Spring Boot 创建任务并写本地 MySQL
4. Spring Boot 返回 OSS 预签名上传地址
5. 前端直传原图到 OSS
6. 前端通知 Spring Boot 上传完成
7. Spring Boot 更新任务为 `UPLOADED`
8. Spring Boot 发布 `detection.task.created`
9. 远程 worker 消费 `detection.task.created`
10. 远程 worker 记录 `started_at`
11. 远程 worker 从 OSS 下载原图
12. 远程 worker 调用 `main.py` 中的检测逻辑执行推理
13. 远程 worker 上传标注图到 `results/preview/`
14. 远程 worker 上传 `detection_results.json`
15. 远程 worker 发布 `detection.task.finished`
16. Spring Boot 消费 `detection.task.finished`
17. Spring Boot 更新本地 MySQL：
    - `status`
    - `started_at`
    - `finished_at`
    - `result_oss_prefix`
    - `result_json_oss_key`
    - `preview_image_keys_json`
    - `statistics_json`
18. 前端轮询 Spring Boot 获取最终结果

---

## 10. Final Status Model

最终状态固定为：

- `UPLOADING`
- `UPLOADED`
- `QUEUED`
- `UPLOADING_RESULT`
- `COMPLETED`
- `PARTIAL_FAILED`
- `FAILED`

状态含义固定为：

- `UPLOADING`：前端正在上传 OSS
- `UPLOADED`：OSS 原图已传完，Spring Boot 已确认
- `QUEUED`：任务已进入 Kafka，远程 worker 尚未开始真正处理
- `UPLOADING_RESULT`：远程 worker 已完成推理，正在回写结果
- `COMPLETED`：任务完全完成
- `PARTIAL_FAILED`：任务处理完成，但存在部分图片失败或部分结果回写失败
- `FAILED`：整个任务失败

当前阶段不把 `DETECTING` 作为数据库主状态写入最终方案。

---

## 11. Final Environment Variables

### 11.1 Local Spring Boot

本地 Spring Boot 固定配置：

```text
APP_KAFKA_ENABLED=true
APP_KAFKA_BOOTSTRAP_SERVERS=<REMOTE_KAFKA_HOST:9092>
APP_KAFKA_CONSUMER_GROUP=doorhandlecatch-detection
APP_KAFKA_TOPIC_TASK_CREATED=detection.task.created
APP_KAFKA_TOPIC_TASK_FINISHED=detection.task.finished
APP_DETECTION_WORKER_ENABLED=true
APP_DETECTION_WORKER_DEPLOYMENT=remote
APP_DETECTION_WORKER_LABEL=remote-python-worker
```

### 11.2 Remote Python Worker

远程 worker 固定配置：

```text
KAFKA_BOOTSTRAP_SERVERS=<REMOTE_KAFKA_HOST:9092>
KAFKA_TASK_CREATED_TOPIC=detection.task.created
KAFKA_TASK_FINISHED_TOPIC=detection.task.finished
KAFKA_CONSUMER_GROUP=doorhandlecatch-python
ALIYUN_OSS_ENDPOINT=<ALIYUN_OSS_ENDPOINT>
ALIYUN_OSS_BUCKET=<ALIYUN_OSS_BUCKET>
ALIYUN_OSS_ACCESS_KEY_ID=<ALIYUN_OSS_ACCESS_KEY_ID>
ALIYUN_OSS_ACCESS_KEY_SECRET=<ALIYUN_OSS_ACCESS_KEY_SECRET>
```

---

## 12. Final Deployment Order

执行顺序固定如下：

### Step 1

在远程服务器部署 Kafka 单实例，并确认本地可以连接。

### Step 2

把这些文件同步到远程现有检测项目目录：

- `kafka_detection_worker.py`
- `kafka_event_models.py`
- `kafka_settings.py`
- `oss_result_uploader.py`
- `requirements-kafka.txt`

### Step 3

在远程检测项目环境中安装：

```text
pip install -r requirements-kafka.txt
```

### Step 4

在远程服务器配置 worker 所需环境变量。

### Step 5

在远程服务器启动：

```text
python kafka_detection_worker.py
```

### Step 6

在本地 Spring Boot 配置远程 Kafka 地址并启动服务。

### Step 7

使用前端做一次真实批量上传和检测联调。

---

## 13. Final Verification Standard

只有同时满足下面全部条件，才算远程方案部署成功：

1. 本地 Spring Boot 能成功向远程 Kafka 发布 `detection.task.created`
2. 远程 worker 能成功消费该消息
3. 远程 worker 能从 OSS 成功读取原图
4. 远程 worker 能调用 `main.py` 的检测逻辑完成推理
5. 远程 worker 能成功上传标注图到 `results/preview/`
6. 远程 worker 能成功上传 `detection_results.json`
7. 远程 worker 能成功发布 `detection.task.finished`
8. 本地 Spring Boot 能成功消费 `detection.task.finished`
9. 本地 MySQL 中 `started_at` 和 `finished_at` 正确落库
10. 前端结果页能看到：
   - 原图目录
   - 结果目录
   - 结果 JSON
   - 检测开始时间
   - 检测结束时间

---

## 14. Final Conclusion

这套项目的远程最终方案已经固定：

1. 远程部署单 Kafka
2. 远程不再作为 Java 调用的 FastAPI HTTP 服务
3. 远程只运行 `kafka_detection_worker.py`
4. `main.py` 只作为检测逻辑模块供 worker 复用
5. 本地 Spring Boot 接远程 Kafka
6. 本地 MySQL 继续保留
7. OSS 作为唯一文件存储层

这就是当前项目的最终正确部署方案。
