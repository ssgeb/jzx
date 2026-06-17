-- 聊天项目表 - 用于分组管理对话
-- 执行时间: 2026-05-30

USE doorhandledb;

-- 创建项目表
CREATE TABLE IF NOT EXISTS `chat_project` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `project_id` VARCHAR(64) NOT NULL COMMENT '项目编号',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `name` VARCHAR(100) NOT NULL COMMENT '项目名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '项目描述',
    `color` VARCHAR(20) DEFAULT '#4f6ef7' COMMENT '项目颜色',
    `sort_order` INT DEFAULT 0 COMMENT '排序顺序',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chat_project_id` (`project_id`),
    KEY `idx_chat_project_username` (`username`),
    CONSTRAINT `fk_chat_project_user` FOREIGN KEY (`username`) REFERENCES `users` (`username`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天项目表';

-- chat_session 表添加 project_id 字段
ALTER TABLE `chat_session`
    ADD COLUMN `project_id` VARCHAR(64) DEFAULT NULL COMMENT '所属项目ID' AFTER `pinned`;

-- 添加索引
ALTER TABLE `chat_session`
    ADD INDEX `idx_chat_session_project` (`project_id`);
