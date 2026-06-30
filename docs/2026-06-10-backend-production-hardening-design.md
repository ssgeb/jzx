# 后端生产化基础优化设计

## 目标

本轮优化聚焦 Spring Boot 后端基础能力，不新增大块业务功能，优先提升接口稳定性、错误可诊断性和部署配置安全性。

## 范围

- 统一全局异常响应，覆盖参数校验、缺失参数、请求体格式错误、资源未找到和业务异常。
- 精简模型管理 Controller 中重复的 `try/catch`，让 Controller 回归路由和参数协调职责。
- 将 CORS 白名单从硬编码改为配置项，便于生产部署按域名收敛。
- 收紧敏感默认配置，避免代码仓库中携带可疑默认 API Key。
- 接入 Spring Boot Actuator，提供标准健康检查、基础指标和平台配置体检能力。

## 设计

- 继续沿用项目现有 `Result<T>` 响应结构，避免影响前端解析。
- `GlobalExceptionHandler` 负责把框架异常转换为稳定业务错误码和中文提示。
- 新增 `AppCorsProperties` 读取 `app.cors.allowed-origins` 等配置，`SecurityConfig` 使用该配置构造 CORS 策略。
- `ModelInfoController` 删除局部异常吞吐逻辑，保留必要的存在性判断。
- `/actuator/health` 对部署探针开放，其它 Actuator 端点保持认证访问。
- 新增 `industrialPlatform` 健康项，展示 Kafka、检测 Worker、模型/检测目录等关键平台配置状态。

## 验证

- 执行 Maven 测试，确保现有服务测试不回归。
- 重点关注模型管理、检测任务、登录鉴权相关测试结果。
- 确认 Actuator 依赖引入后编译和测试通过。
