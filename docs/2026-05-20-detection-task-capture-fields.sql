ALTER TABLE `detection_task`
    ADD COLUMN `capture_date` VARCHAR(32) DEFAULT NULL COMMENT '采集日期' AFTER `threshold`,
    ADD COLUMN `region` VARCHAR(64) DEFAULT NULL COMMENT '地区' AFTER `capture_date`,
    ADD COLUMN `collector` VARCHAR(64) DEFAULT NULL COMMENT '采集员' AFTER `region`,
    ADD COLUMN `device_name` VARCHAR(128) DEFAULT NULL COMMENT '采集设备' AFTER `collector`;
