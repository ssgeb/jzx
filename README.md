# 门把手检测系统 (Door Handle Catch System)

这是一个基于深度学习的门把手状态检测系统，可以自动识别门把手的状态（正常、需要维修或需要更换）。

## 系统架构

- **前端**：Vue.js + Ant Design Vue
- **后端**：Spring Boot + MyBatis Plus
- **数据库**：MySQL
- **AI推理**：ONNX Runtime + OpenCV

## 功能特点

- 门把手状态分类（正常、需要维修、需要更换）
- 实时图像检测及结果可视化
- 带有颜色标记的检测框和标签
- 检测历史记录管理
- 模型管理与切换

## 安装步骤

### 前提条件

- JDK 17+
- Maven 3.6+
- Node.js 14+
- MySQL 8.0+

### 数据库设置

1. 安装MySQL数据库
2. 创建数据库：
```sql
CREATE DATABASE doorhandledb DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 后端设置

1. 克隆代码库
2. 修改配置文件 `src/main/resources/application.yml` 中的数据库配置
3. 编译项目：
```bash
mvn clean compile
```

### 前端设置

1. 进入前端目录：
```bash
cd frontend
```

2. 安装依赖：
```bash
npm install
```

3. 启动开发服务器：
```bash
npm run dev
```

## 使用指南

完整的业务操作说明请查看：

```text
docs/system-user-guide.md
```

智能助手 Chroma RAG 配置说明请查看：

```text
docs/chroma-rag-setup.md
```

### 启动后端

直接通过主类运行Spring Boot应用程序：

```bash
# 普通启动
java -cp target/classes;target/dependency/* com.ruanzhu.doorhandlecatch.DoorHandleCatchApplication

# 调试模式启动
java -Ddebug=true -cp target/classes;target/dependency/* com.ruanzhu.doorhandlecatch.DoorHandleCatchApplication

# 初始化数据库
java -cp target/classes;target/dependency/* com.ruanzhu.doorhandlecatch.DoorHandleCatchApplication --init-database

# 清理图片缓存
java -cp target/classes;target/dependency/* com.ruanzhu.doorhandlecatch.DoorHandleCatchApplication --clean-images
```

在IDE中，可以直接运行`DoorHandleCatchApplication.java`文件，并根据需要添加命令行参数。

### 启动前端（开发模式）

```bash
cd frontend
npm run dev
```

### 图像检测

1. 访问系统：http://localhost:3001
2. 登录系统（默认管理员账号：admin/admin）
3. 进入"图像检测"页面
4. 上传门把手图像
5. 选择检测模型（可选）
6. 点击"开始检测"按钮
7. 查看检测结果（包含检测框、类别和置信度）

### 检测结果说明

- **绿色框**：正常状态
- **橙色框**：需要维修
- **红色框**：需要更换

## 常见问题解决

### 中文显示乱码

系统默认使用UTF-8编码，如果仍然出现乱码，可以通过添加JVM参数解决：

```bash
java -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp target/classes;target/dependency/* com.ruanzhu.doorhandlecatch.DoorHandleCatchApplication
```

### 数据库连接问题

请确认MySQL服务已启动，并检查用户名密码是否正确。

### 图像上传失败

请检查图像格式（支持JPG/JPEG/PNG），并确保文件大小不超过限制（默认5MB）。

## 许可证

MIT 
