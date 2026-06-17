# 检测模块接入 OSS 总体设计方案

## 1. 文档目的

本文档用于定义当前项目的检测模块在接入阿里云 OSS 之后的最终总体方案，重点解决以下问题：

- 原图如何上传
- 当前项目如何与远程 FastAPI 检测服务对接
- 检测结果如何回传和存储
- 前端如何查看任务进度和部分结果图
- `detection_results.json` 如何同时保留本地副本和云端副本

本文档是主方案文档，面向当前项目本身。

---

## 2. 系统边界

当前方案涉及四个角色：

### 2.1 前端

负责：

- 创建检测任务
- 获取原图上传预签名
- 原图直传 OSS
- 查询任务状态
- 查看检测结果摘要和部分预览图

不负责：

- 直接调用 FastAPI
- 上传检测结果图
- 生成检测结果 JSON

### 2.2 当前项目的 Java 后端

负责：

- 创建检测任务
- 生成原图上传预签名
- 接收前端上传完成通知
- 调用远程 FastAPI 检测服务
- 记录任务状态和结果摘要
- 统一向前端返回任务状态、预览图和结果入口

不负责：

- 执行模型推理
- 生成标注图
- 中转大批量结果图上传

### 2.3 远程 FastAPI 检测服务

负责：

- 从 OSS 拉取原图
- 在远程服务器执行模型检测
- 生成标注图
- 生成 `detection_results.json`
- 将检测结果上传回 OSS
- 将检测摘要返回给当前项目

说明：

- FastAPI 是远程独立项目
- 当前仓库不承载 FastAPI 服务本体

### 2.4 OSS

负责：

- 存储原图
- 存储标注图
- 存储结果 JSON
- 存储任务元信息文件

---

## 3. 总体方案结论

最终方案确定为：

1. 原图通过“前端直传 OSS + 后端预签名”的方式上传
2. 前端上传完成后通知 Java 后端
3. Java 后端调用远程 FastAPI 检测服务
4. 远程 FastAPI 从 OSS 拉取原图并执行检测
5. 检测完成后由远程 FastAPI 直接上传标注图和结果 JSON 到 OSS
6. Java 后端统一维护任务状态，并向前端输出结果摘要和部分预览图

这是当前项目检测模块接入 OSS 的最终推荐架构。

---

## 4. 核心设计原则

### 4.1 前端只直传原图

前端只负责把原图传到 OSS，不参与检测结果上传。

原因：

- 结果图和 JSON 是检测服务产物，应该由服务端生成并上传
- 可以避免前端伪造检测结果
- 结果目录权限更容易收口

### 4.2 当前项目只认 Java 后端

前端不直接调用 FastAPI，只调用当前项目的 Java 后端。

原因：

- 任务状态统一
- 模型参数统一
- 权限与日志统一
- 后续扩展更容易

### 4.3 远程 FastAPI 只做检测执行

远程 FastAPI 的职责聚焦在：

- 拉取原图
- 执行检测
- 生成结果
- 上传结果

不承担前端业务和权限管理。

### 4.4 批量任务只返回部分预览图

批量检测完成后，前端只展示少量预览图，不一次性加载全部结果图。

原因：

- 避免页面卡顿
- 避免结果响应过大
- 全量结果更适合后续通过分页能力或 OSS 浏览能力承载

---

## 5. 总体业务流程

完整链路如下：

1. 前端请求 Java 后端创建检测任务
2. Java 后端生成 `taskId`、上传前缀和每个文件的预签名上传 URL
3. 前端通过预签名 URL 将原图直传到 OSS
4. 前端调用 Java 后端确认上传完成，并提交 OSS key 列表
5. Java 后端记录任务并调用远程 FastAPI 检测服务
6. 远程 FastAPI 从 OSS 下载原图到远程服务器本地临时目录
7. 远程 FastAPI 加载模型执行检测
8. 远程 FastAPI 生成标注图和 `detection_results.json`
9. 远程 FastAPI 将标注图和 JSON 上传回 OSS
10. 远程 FastAPI 把任务摘要返回给 Java 后端
11. Java 后端更新任务状态
12. 前端查询任务状态和结果，展示统计信息与预览图

可简化为三条链路：

- 原图上传链路：前端 -> OSS
- 检测执行链路：Java 后端 -> 远程 FastAPI -> OSS
- 前端展示链路：前端 <- Java 后端 <- 任务状态 / 结果摘要 / 预览图

---

## 6. OSS 目录设计

每个任务使用独立目录，建议结构如下：

```text
detection/{taskId}/
  originals/
  annotated/
  results/
    detection_results.json
  meta/
    task.json
```

目录说明：

- `originals/`：原图，由前端通过预签名直传
- `annotated/`：标注结果图，由远程 FastAPI 上传
- `results/detection_results.json`：检测结果 JSON，由远程 FastAPI 上传，同时本地保留副本
- `meta/task.json`：任务元信息文件，可由远程 FastAPI 或 Java 后端生成

建议：

- `taskId` 由 Java 后端统一生成
- OSS key 由 Java 后端统一规划，前端不自定义完整路径

---

## 7. 前端方案

### 7.1 前端需要支持的能力

前端需要支持：

- 调用创建任务接口
- 根据预签名 URL 上传原图
- 上传完成后提交 OSS key 列表
- 轮询或刷新任务状态
- 展示任务阶段、进度、错误信息
- 展示部分预览图
- 提供结果 JSON 下载入口

### 7.2 前端展示内容

#### 单图检测

前端展示：

- 分类结果
- 置信度
- 单张标注图

#### 批量检测

前端展示：

- 总图数
- 成功数
- 失败数
- 分类统计
- 少量预览图
- 结果 JSON 下载入口
- OSS 归档状态

---

## 8. Java 后端方案

### 8.1 Java 后端核心职责

Java 后端负责：

- 任务创建
- 上传预签名生成
- 原图上传确认
- 任务状态记录
- 调用远程 FastAPI
- 保存检测结果摘要
- 为前端签发预览图和 JSON 的访问 URL

### 8.2 Java 后端对前端提供的接口

建议至少提供：

1. `POST /api/detection/tasks`
   - 创建任务并返回原图上传预签名
2. `POST /api/detection/tasks/{taskId}/uploaded`
   - 确认原图上传完成并触发检测
3. `GET /api/detection/tasks/{taskId}`
   - 查询任务状态
4. `GET /api/detection/tasks/{taskId}/result`
   - 查询任务结果摘要

### 8.3 Java 后端对远程 FastAPI 的职责

Java 后端通过接口调用远程 FastAPI，并将其视为外部依赖服务。

需要特别处理：

- 服务地址配置
- 连接超时
- 调用失败
- 返回结构校验
- 状态回写

---

## 9. 远程 FastAPI 方案

### 9.1 远程 FastAPI 的职责

远程 FastAPI 负责：

- 从 OSS 拉原图
- 在远程服务器本地执行模型推理
- 输出标注图
- 生成 `detection_results.json`
- 直接上传结果到 OSS
- 返回结果摘要给 Java 后端

### 9.2 远程 FastAPI 的返回内容

建议远程 FastAPI 返回：

- `taskId`
- `status`
- `totalImages`
- `successfulImages`
- `failedImages`
- `statistics`
- `previewKeys`
- `resultJsonKey`
- `resultOssPrefix`

返回的是摘要和对象 key，不返回全量图片内容。

---

## 10. detection_results.json 设计

`detection_results.json` 采用双写策略：

- 远程 FastAPI 在远程服务器本地保留一份
- 同时上传一份到 OSS

这样做的目的：

- 云端上传失败时可保留本地副本
- 便于问题排查
- 支持后续重传和恢复

建议 JSON 包含：

- 任务基础信息
- 模型信息
- 阈值
- 总体统计
- 每张图的检测明细

---

## 11. 任务状态设计

建议任务状态如下：

- `PENDING`
- `UPLOADING`
- `UPLOADED`
- `DETECTING`
- `UPLOADING_RESULT`
- `COMPLETED`
- `FAILED`
- `PARTIAL_FAILED`

说明：

- `PENDING`：任务已创建，尚未开始上传
- `UPLOADING`：前端正在上传原图到 OSS
- `UPLOADED`：原图已上传完成，等待远程检测
- `DETECTING`：远程 FastAPI 正在执行检测
- `UPLOADING_RESULT`：远程 FastAPI 正在上传结果到 OSS
- `COMPLETED`：检测和结果归档均成功
- `FAILED`：主链路失败
- `PARTIAL_FAILED`：检测已成功，但结果归档不完整

---

## 12. 安全设计

### 12.1 上传预签名权限

前端上传预签名只允许写入：

```text
detection/{taskId}/originals/
```

禁止前端写入：

- `annotated/`
- `results/`
- `meta/`

### 12.2 预签名时效

建议上传预签名有效期控制在：

- 5 到 15 分钟

### 12.3 结果访问控制

建议：

- OSS 使用私有桶
- 由 Java 后端按需生成预览图和 JSON 的访问签名 URL

---

## 13. 异常处理

### 13.1 原图上传失败

- 前端允许重传失败文件
- 任务状态保持在 `UPLOADING`

### 13.2 上传成功但未确认

- Java 后端可定期扫描超时任务
- 长时间未确认的任务可关闭或标记异常

### 13.3 远程 FastAPI 调用失败

- Java 后端将任务标记为 `FAILED`
- 原图仍保留在 OSS

### 13.4 结果上传 OSS 失败

- 任务标记为 `PARTIAL_FAILED`
- 本地保留 `detection_results.json`
- 后续支持重传

### 13.5 预览图生成不完整

- 不阻断任务整体完成
- 允许主任务完成但预览图部分缺失

---

## 14. 第一版落地范围

第一版建议只做最小闭环：

1. Java 后端创建任务并签发原图上传预签名
2. 前端直传原图 OSS
3. 前端确认上传完成
4. Java 后端调用远程 FastAPI
5. 远程 FastAPI 从 OSS 拉图并检测
6. 远程 FastAPI 上传标注图和 JSON 到 OSS
7. Java 后端提供任务状态和结果查询
8. 前端展示状态、统计和少量预览图

第一版暂不做：

- 分片上传
- 断点续传
- 全量结果图分页浏览
- 人工复核
- 任务取消和自动重试

---

## 15. 结论

当前项目检测模块接入 OSS 的最终方案可以概括为：

- 原图前端直传 OSS
- 任务由 Java 后端统一编排
- 检测由远程 FastAPI 独立执行
- 结果由远程 FastAPI 直接上传 OSS
- 前端只通过 Java 后端查看进度和结果

该方案能够同时满足：

- 上传链路轻量
- 检测服务职责清晰
- 当前项目边界明确
- 结果回显可控
- 后续扩展方便
