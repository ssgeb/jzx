package com.ruanzhu.doorhandlecatch.dto.chat;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AgentExecutionResult {

    private String messageType;
    private String content;
    private String intent;
}
