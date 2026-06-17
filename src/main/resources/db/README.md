# 检测记录数据导入说明

本文档介绍如何向数据库中导入检测记录数据。

## 文件说明

- `detection_records_500_samples.sql`: 包含500条检测记录的SQL文件
- `detection_records_generator.js`: 用于生成检测记录数据的JavaScript脚本
- `import_detection_records.bat`: Windows批处理文件，用于自动导入数据

## 导入方法

### 方法一：使用批处理文件（Windows）

1. 修改 `import_detection_records.bat` 文件中的数据库连接信息（用户名、密码等）
2. 双击运行 `import_detection_records.bat`

### 方法二：使用MySQL命令行

```bash
# Linux/Mac
mysql -u用户名 -p密码 数据库名 < detection_records_500_samples.sql

# Windows (CMD)
mysql -u用户名 -p密码 数据库名 < detection_records_500_samples.sql

# Windows (PowerShell)
Get-Content detection_records_500_samples.sql | mysql -u用户名 -p密码 数据库名
```

### 方法三：使用MySQL客户端工具

1. 打开MySQL Workbench或其他图形客户端工具
2. 连接到数据库
3. 打开并执行 `detection_records_500_samples.sql` 文件

## 数据生成

如果需要生成更多或自定义的数据，可以使用 `detection_records_generator.js` 脚本：

```bash
# 使用Node.js执行脚本
node detection_records_generator.js > new_records.sql

# 然后导入生成的SQL文件
mysql -u用户名 -p密码 数据库名 < new_records.sql
```

## 数据规格

生成的检测记录数据符合以下要求：
1. 时间范围：2021年到2025年
2. 图片漏检率不超过2%
3. 正常类别目标数量不超过2%
4. 更换类别目标数量大于维修类别目标数量
5. 三个类别的目标总数大于图片总数（一张图片可能有多个目标）
6. 检测速度：每秒15张图片
7. 图片总数范围：5000-20000张 