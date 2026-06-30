package com.ruanzhu.doorhandlecatch.dto.chat;

import lombok.Data;

@Data
public class ChatPendingActionPayload {

    private String username;
    private String userPrompt;
    private String intent;
    private String targetAgent;
}
