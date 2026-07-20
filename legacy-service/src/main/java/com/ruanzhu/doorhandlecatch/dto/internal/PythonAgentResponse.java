package com.ruanzhu.doorhandlecatch.dto.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PythonAgentResponse {

    @JsonProperty("request_id")
    private String requestId;
    private String content;
    @JsonProperty("result_type")
    private String resultType;
    private String intent;
    private PythonAgentAction action;
    private Map<String, Object> checkpoint = new LinkedHashMap<>();
    @JsonProperty("exit_reason")
    private String exitReason;
    private List<String> trace = new ArrayList<>();
}
