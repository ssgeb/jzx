package com.ruanzhu.doorhandlecatch.dto.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PythonSkillInstallRequest {
    private String repository;
    private String path;
    private String ref;
    @JsonProperty("requested_by")
    private String requestedBy;
}
