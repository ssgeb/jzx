ALTER TABLE `image_detection_data`
    ADD COLUMN `normal_count` INT DEFAULT 0 COMMENT '正常数量' AFTER `detected_images_count`,
    ADD COLUMN `bent_count` INT DEFAULT 0 COMMENT '弯曲数量' AFTER `normal_count`,
    ADD COLUMN `deformed_count` INT DEFAULT 0 COMMENT '形变数量' AFTER `bent_count`,
    ADD COLUMN `rusty_count` INT DEFAULT 0 COMMENT '锈蚀数量' AFTER `deformed_count`,
    ADD COLUMN `missing_count` INT DEFAULT 0 COMMENT '缺失数量' AFTER `rusty_count`,
    ADD COLUMN `compromised_count` INT DEFAULT 0 COMMENT '结构损伤数量' AFTER `missing_count`;

UPDATE `image_detection_data`
SET
    `normal_count` = COALESCE(`bsgxx_count`, 0),
    `bent_count` = COALESCE(`bsgzx_count`, 0),
    `deformed_count` = COALESCE(`bsggh_count`, 0),
    `rusty_count` = COALESCE(`rusty_count`, 0),
    `missing_count` = COALESCE(`missing_count`, 0),
    `compromised_count` = COALESCE(`compromised_count`, 0);

ALTER TABLE `image_detection_data`
    DROP COLUMN `bsgxx_count`,
    DROP COLUMN `bsgzx_count`,
    DROP COLUMN `bsggh_count`;
