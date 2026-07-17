# 分布式改造基线

- 执行日期：2026-07-18
- 目标分支：`feature/microservices-distributed`
- 隔离工作区：`.worktrees/microservices-execution`（分离头指针，仅用于安全实施）
- Maven：`D:\ruanjian\apache-maven-3.9.6`
- Java：17
- 后端命令：`& 'D:\ruanjian\apache-maven-3.9.6\bin\mvn.cmd' test`
- 后端结果：216 个测试通过，0 个失败，0 个错误，0 个跳过；Maven 构建成功
- 前端命令：进入 `frontend` 后执行 `node --test tests/*.test.cjs`
- 前端结果：42 个测试通过，0 个失败，0 个取消，0 个跳过
- 已知外部依赖：MySQL、Redis、Kafka、OSS、Mem0、Chroma、DeepSeek
- 基线提交：`384e33d81d1dd41db8972b40fa830756fe0302ee`

## 说明

前端的 `package.json` 当前只定义了 `dev`、`build` 和 `preview`，没有定义 `test` 脚本。因此基线使用项目现有 Node.js 契约测试的真实入口 `node --test tests/*.test.cjs`，实施计划中的复测命令也已同步修正。

该基线用于判断 Maven 多模块拆分是否改变了原单体服务和前端的既有行为。后续结构调整完成后，应再次执行相同测试并与本记录对比。
