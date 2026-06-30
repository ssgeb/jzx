package com.ruanzhu.doorhandlecatch.dto.detection;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DetectionUploadedFileItem {
    @NotBlank(message = "文件名不能为空")
    private String fileName;
    @NotBlank(message = "对象 key 不能为空")
    private String objectKey;
    private String etag;
}
