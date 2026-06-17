package com.ruanzhu.doorhandlecatch.dto.detection;

import lombok.Data;

@Data
public class DetectionReworkResultRequest {
    private String reworkResult;
    private String reworkOperator;
    private String reworkRemark;
    private Boolean recheckRequired;
}
