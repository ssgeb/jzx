package com.ruanzhu.doorhandlecatch.dto.detection;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DetectionPreviewImage {
    private String imageName;
    private String originalUrl;
    private String annotatedUrl;
}
