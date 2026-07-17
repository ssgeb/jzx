package com.ruanzhu.doorhandlecatch.dto.detection;

import lombok.Data;

@Data
public class DetectionTaskAssignmentRequest {
    private String qualityStation;
    private String assignee;
    private String dueAt;
    private String assignmentRemark;
}
