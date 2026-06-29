-- V14: Persist user roles and task reliability metadata.
-- Every DDL operation is guarded for safe repeat execution.

DELIMITER //

DROP PROCEDURE IF EXISTS v14_add_column_if_missing//
CREATE PROCEDURE v14_add_column_if_missing(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND COLUMN_NAME = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

DROP PROCEDURE IF EXISTS v14_add_index_if_missing//
CREATE PROCEDURE v14_add_index_if_missing(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_definition TEXT)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table
          AND INDEX_NAME = p_index
    ) THEN
        SET @ddl = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END//

DELIMITER ;

CALL v14_add_column_if_missing('users', 'role',
    '`role` VARCHAR(16) NOT NULL DEFAULT ''OPERATOR''');
CALL v14_add_column_if_missing('detection_task', 'dispatch_id',
    '`dispatch_id` VARCHAR(64) NULL');
CALL v14_add_column_if_missing('detection_task', 'last_finished_event_id',
    '`last_finished_event_id` VARCHAR(128) NULL');
CALL v14_add_column_if_missing('chat_pending_action', 'error_message',
    '`error_message` VARCHAR(500) NULL');

UPDATE `users`
SET `role` = 'ADMIN'
WHERE LOWER(TRIM(`username`)) = 'admin';

UPDATE `users`
SET `role` = 'OPERATOR'
WHERE `role` IS NULL
   OR TRIM(`role`) = ''
   OR UPPER(TRIM(`role`)) NOT IN ('ADMIN', 'OPERATOR');

CALL v14_add_index_if_missing('detection_task', 'idx_detection_task_dispatch_id',
    '(`dispatch_id`)');

DROP PROCEDURE IF EXISTS v14_add_column_if_missing;
DROP PROCEDURE IF EXISTS v14_add_index_if_missing;
