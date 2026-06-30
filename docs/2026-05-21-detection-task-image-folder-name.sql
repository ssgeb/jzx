ALTER TABLE `detection_task`
    ADD COLUMN `image_folder_name` VARCHAR(128) DEFAULT NULL COMMENT '图片文件夹名称' AFTER `device_name`;
