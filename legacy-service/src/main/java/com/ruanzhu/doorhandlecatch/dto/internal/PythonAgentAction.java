package com.ruanzhu.doorhandlecatch.dto.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PythonAgentAction {

    @JsonProperty("action_id")
    private String actionId;
    private String intent;
    @JsonProperty("target_agent")
    private String targetAgent;
    private String preview;
    @JsonProperty("task_prompt")
    private String taskPrompt;
    private Map<String, Object> parameters = new LinkedHashMap<>();
}
