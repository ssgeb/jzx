-- 聊天会话表添加置顶字段和删除支持
-- 执行时间: 2026-05-29

USE doorhandledb;

-- 添加 pinned 字段
ALTER TABLE `chat_session`
    ADD COLUMN `pinned` TINYINT(1) DEFAULT 0 COMMENT '是否置顶' AFTER `status`;

-- 添加索引优化排序查询
ALTER TABLE `chat_session`
    ADD INDEX `idx_chat_session_pinned_updated` (`pinned` DESC, `updated_at` DESC);
