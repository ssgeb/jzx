-- Existing detection_task capture metadata migration.
ALTER TABLE `detection_task`
    ADD COLUMN IF NOT EXISTS `capture_date` VARCHAR(32) DEFAULT NULL COMMENT '采集日期' AFTER `threshold`,
    ADD COLUMN IF NOT EXISTS `region` VARCHAR(64) DEFAULT NULL COMMENT '地区' AFTER `capture_date`,
    ADD COLUMN IF NOT EXISTS `collector` VARCHAR(64) DEFAULT NULL COMMENT '采集员' AFTER `region`,
    ADD COLUMN IF NOT EXISTS `device_name` VARCHAR(128) DEFAULT NULL COMMENT '采集设备' AFTER `collector`;

-- Kafka deployment config checklist (set in environment/application config, not in SQL):
-- APP_KAFKA_ENABLED=true
-- APP_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
-- APP_KAFKA_CONSUMER_GROUP=doorhandlecatch-detection
-- APP_KAFKA_TOPIC_TASK_CREATED=detection.task.created
-- APP_KAFKA_TOPIC_TASK_FINISHED=detection.task.finished
-- KAFKA_BOOTSTRAP_SERVERS=localhost:9092
-- KAFKA_TASK_CREATED_TOPIC=detection.task.created
-- KAFKA_TASK_FINISHED_TOPIC=detection.task.finished
-- KAFKA_CONSUMER_GROUP=doorhandlecatch-python
