# 远程 FastAPI 项目实现与对接方案

## 1. 文档目的

本文档用于定义远程 FastAPI 检测服务应该如何实现，以及它如何和当前项目完成对接。

本文档面向两个对象：

- 远程 FastAPI 项目实现方
- 当前项目的 Java 后端对接方

目标是明确：

- 远程 FastAPI 项目的职责范围
- 它需要实现哪些接口
- 它如何从 OSS 拉原图并把结果回传 OSS
- 它如何把任务摘要回传给当前项目

本文档是面向远程检测服务的主方案文档。

---

## 2. 项目定位

远程 FastAPI 项目是一个独立部署的检测执行服务，不属于当前项目代码仓。

它的正确定位是：

- 接收来自 Java 主项目的检测任务
- 从 OSS 读取原图
- 在远程服务器本地完成模型推理
- 生成检测产物
- 将结果上传回 OSS
- 把结果摘要返回给 Java 主项目

它不是：

- 前端直连服务
- 业务权限中心
- 管理后台
- OSS 上传签名服务

---

## 3. 远程 FastAPI 的职责范围

远程 FastAPI 负责：

- 接收检测任务请求
- 校验模型和参数
- 从 OSS 下载原图到远程服务器本地目录
- 执行单图或批量检测
- 生成标注图
- 生成 `detection_results.json`
- 上传标注图和 JSON 到 OSS
- 返回任务摘要、统计信息和预览图 key

远程 FastAPI 不负责：

- 给前端签发上传预签名
- 管理登录和权限
- 给前端直接提供结果查询接口
- 直接处理浏览器上传

---

## 4. 输入输出约定

## 4.1 输入来源

远程 FastAPI 只接受来自 Java 主项目的调用。

它的输入不是图片二进制本体，而是：

- `taskId`
- `bucketName`
- 原图对象 key 列表
- `modelId`
- `threshold`

这样可以避免 Java 和 FastAPI 之间传输大量图片内容。

## 4.2 输出结果

远程 FastAPI 的输出不是全量图片内容，而是：

- 任务状态
- 成功数 / 失败数
- 统计结果
- 预览图 key 列表
- `detection_results.json` 对象 key

最终图片访问 URL 由 Java 后端统一生成。

---

## 5. 远程 FastAPI 内部目录设计

建议远程 FastAPI 每个任务使用独立工作目录：

```text
workdir/{taskId}/
  originals/
  annotated/
  results/
  tmp/
```

说明：

- `originals/`：从 OSS 下载的原图
- `annotated/`：检测生成的标注图
- `results/`：结果 JSON
- `tmp/`：中间文件和临时缓存

注意：

- 这里的本地目录属于远程 FastAPI 所在服务器
- 不是当前 Java 项目所在机器的本地目录

---

## 6. 远程 FastAPI 处理流程

远程 FastAPI 对每个任务执行如下流程。

### 6.1 接收任务

接收 Java 后端请求：

```json
{
  "taskId": "det_20260517_0001",
  "bucketName": "your-bucket",
  "originalKeys": [
    "detection/det_20260517_0001/originals/a001.jpg"
  ],
  "modelId": "model_001",
  "threshold": 0.4
}
```

### 6.2 创建工作目录

在远程服务器上创建：

```text
workdir/{taskId}/
```

### 6.3 从 OSS 下载原图

根据 `originalKeys` 下载原图到：

```text
workdir/{taskId}/originals/
```

建议记录：

- 总图数
- 下载成功数
- 下载失败数

### 6.4 执行检测

逐张或按批次处理图片：

1. 读取原图
2. 加载模型
3. 执行推理
4. 生成标注图
5. 记录单图检测结果

### 6.5 生成结果文件

生成：

- `results/detection_results.json`

建议内容包括：

- 任务基础信息
- 模型信息
- 阈值
- 图片结果明细
- 统计结果

同时建议生成：

- `meta/task.json`

### 6.6 上传结果到 OSS

上传内容：

- 标注图
- `detection_results.json`
- `meta/task.json`

上传目标：

```text
detection/{taskId}/annotated/
detection/{taskId}/results/detection_results.json
detection/{taskId}/meta/task.json
```

### 6.7 返回任务摘要

把任务摘要返回给 Java 后端。

---

## 7. OSS 交互方案

## 7.1 原图获取

远程 FastAPI 使用服务端 OSS 凭证下载原图，不依赖前端签名 URL。

原因：

- 上传预签名主要用于浏览器上传
- 服务端更适合使用固定 OSS 凭证访问
- 避免下载链接过期导致检测失败

## 7.2 结果上传

远程 FastAPI 使用服务端 OSS 凭证上传：

- 标注图
- `detection_results.json`
- 元信息文件

## 7.3 本地与云端双写

`detection_results.json` 必须保留双写：

- 远程服务器本地保留一份
- OSS 上传一份

作用：

- 便于排障
- 云端上传失败时保留恢复依据

---

## 8. 对外接口设计

第一版建议远程 FastAPI 只暴露内部接口。

## 8.1 发起 OSS 检测任务

### 接口

`POST /internal/detection/oss-task`

### 请求体

```json
{
  "taskId": "det_20260517_0001",
  "bucketName": "your-bucket",
  "originalKeys": [
    "detection/det_20260517_0001/originals/a001.jpg",
    "detection/det_20260517_0001/originals/a002.jpg"
  ],
  "modelId": "model_001",
  "threshold": 0.4
}
```

### 返回体

```json
{
  "taskId": "det_20260517_0001",
  "status": "success",
  "totalImages": 200,
  "successfulImages": 194,
  "failedImages": 6,
  "statistics": {
    "bsgxxCount": 120,
    "bsgzxCount": 52,
    "bsgghCount": 22,
    "noDetectionImages": 10,
    "missDetectionRate": 5.0
  },
  "previewKeys": [
    "detection/det_20260517_0001/annotated/a001.jpg",
    "detection/det_20260517_0001/annotated/a002.jpg"
  ],
  "resultJsonKey": "detection/det_20260517_0001/results/detection_results.json",
  "resultOssPrefix": "detection/det_20260517_0001/"
}
```

### 状态值建议

- `success`
- `failed`
- `partial_failed`

## 8.2 任务进度查询接口（可选）

### 接口

`GET /internal/detection/tasks/{taskId}/progress`

### 作用

- 给 Java 后端提供轮询能力
- 便于前端任务面板展示阶段和进度

### 返回体

```json
{
  "taskId": "det_20260517_0001",
  "status": "DETECTING",
  "stage": "DETECTING",
  "currentBatch": 3,
  "totalBatches": 5,
  "processedImages": 128,
  "totalImages": 200,
  "progressPercent": 64,
  "message": "正在检测第 3 / 5 批"
}
```

如果第一版先采用同步等待结果，也可以暂时不启用该接口。

---

## 9. 预览图策略

远程 FastAPI 不返回全量结果图，只返回少量预览图 key。

建议：

- 单图任务：返回 1 张结果图 key
- 批量任务：返回 3 到 9 张预览图 key

预览图选择建议：

- 优先选检测成功图片
- 优先选代表性类别图片
- 优先选分数较高或较有代表性的结果

---

## 10. Java 与远程 FastAPI 的对接方式

## 10.1 调用时机

Java 后端应在前端确认原图上传完成后调用远程 FastAPI。

时机要求：

- 原图已上传到 OSS
- OSS key 校验通过
- 任务状态已进入 `UPLOADED`

然后：

- Java 发起远程调用
- 任务状态改为 `DETECTING`

## 10.2 Java 需要接收的字段

Java 至少要接收并持久化：

- `taskId`
- `status`
- `totalImages`
- `successfulImages`
- `failedImages`
- `statistics`
- `previewKeys`
- `resultJsonKey`
- `resultOssPrefix`

## 10.3 Java 对前端的再封装

Java 不应把远程 FastAPI 原始结构直接透传给前端。

Java 应负责二次封装：

- 状态字段统一
- 错误口径统一
- 预览图 URL 签名
- JSON 下载地址签名

---

## 11. 异常处理

## 11.1 远程 FastAPI 侧异常

需要处理：

- OSS 下载失败
- 模型加载失败
- 单张图片推理失败
- 标注图生成失败
- OSS 结果上传失败

建议处理原则：

- 单图失败不直接打断整个批量任务
- 能继续执行则继续执行
- 最终汇总成功数和失败数

## 11.2 状态映射建议

远程 FastAPI 返回：

- `success` -> Java 任务状态 `COMPLETED`
- `failed` -> Java 任务状态 `FAILED`
- `partial_failed` -> Java 任务状态 `PARTIAL_FAILED`

---

## 12. 临时文件清理

远程 FastAPI 建议支持两种模式：

### 12.1 开发环境

- 检测完成后保留工作目录一段时间
- 便于排查问题

### 12.2 生产环境

- 检测和上传完成后延迟清理
- 或通过定时任务清理过期工作目录

建议：

- `COMPLETED` 任务可较快清理
- `FAILED` 任务保留更久，便于排查

---

## 13. 第一版实现建议

远程 FastAPI 第一版建议优先实现：

1. OSS 原图下载能力
2. 本地工作目录管理
3. 模型推理能力
4. 标注图生成
5. `detection_results.json` 生成
6. 结果上传 OSS
7. 结果摘要返回

第一版暂缓实现：

- 分布式任务队列
- 自动恢复未完成任务
- 高级重试机制
- 复杂任务管理后台

---

## 14. 联调建议

建议联调顺序如下：

1. 先联通 Java -> 远程 FastAPI 的健康检查
2. 再联通 Java -> 远程 FastAPI 的模拟检测接口
3. 再联通远程 FastAPI -> OSS 下载与上传
4. 最后联通前端直传 OSS + Java 任务流

建议远程 FastAPI 先准备一个“模拟检测模式”：

- 不做真实推理
- 返回固定结果结构

这样 Java 和前端可以先跑通整体链路。

---

## 15. 结论

远程 FastAPI 项目的最终实现目标是：

- 作为独立检测执行服务存在
- 从 OSS 拉原图
- 在远程服务器完成检测
- 将结果直传回 OSS
- 将结果摘要返回给当前项目

当前项目与远程 FastAPI 的关系是：

- 当前项目负责业务编排
- 远程 FastAPI 负责检测执行
- 两者通过明确的内部接口完成绑定

这就是远程 FastAPI 项目实现与当前项目对接的最终方案。
