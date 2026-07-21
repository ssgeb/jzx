package com.ruanzhu.doorhandlecatch.dto.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PythonSkillRecord {
    private String name;
    private String description;
    private String repository;
    @JsonProperty("source_path")
    private String sourcePath;
    private String ref;
    private String checksum;
    private String status;
    @JsonProperty("installed_at")
    private String installedAt;
    @JsonProperty("installed_by")
    private String installedBy;
}
