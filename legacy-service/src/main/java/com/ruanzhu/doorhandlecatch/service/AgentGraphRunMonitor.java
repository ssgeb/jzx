package com.ruanzhu.doorhandlecatch.service;

import com.ruanzhu.doorhandlecatch.dto.chat.AgentGraphHealthResponse;
import com.ruanzhu.doorhandlecatch.stategraph.core.AgentGraphRunListener;

public interface AgentGraphRunMonitor extends AgentGraphRunListener {

    AgentGraphHealthResponse snapshot();
}
