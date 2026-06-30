package com.ruanzhu.doorhandlecatch.service.agent.impl;

import com.ruanzhu.doorhandlecatch.dto.chat.AgentExecutionResult;
import com.ruanzhu.doorhandlecatch.entity.Device;
import com.ruanzhu.doorhandlecatch.service.DeepSeekClient;
import com.ruanzhu.doorhandlecatch.service.DeviceService;
import com.ruanzhu.doorhandlecatch.service.EmployeeService;
import com.ruanzhu.doorhandlecatch.service.ModelService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceAgentServiceImplTest {

    @Test
    void fallbackDeviceAnswerIncludesSystemDataSource() {
        DeviceService deviceService = mock(DeviceService.class);
        EmployeeService employeeService = mock(EmployeeService.class);
        ModelService modelService = mock(ModelService.class);
        DeepSeekClient deepSeekClient = mock(DeepSeekClient.class);

        Device device = new Device();
        device.setDeviceCode("CAM-01");
        device.setStatus("ACTIVE");
        device.setOnlineStatus("ONLINE");
        device.setCaptureStatus("IDLE");

        when(deviceService.getAllDevices()).thenReturn(List.of(device));
        when(deepSeekClient.generateResourceResponse(anyString(), anyString()))
                .thenThrow(new RuntimeException("llm unavailable"));

        ResourceAgentServiceImpl service = new ResourceAgentServiceImpl(
                deviceService,
                employeeService,
                modelService,
                deepSeekClient
        );

        AgentExecutionResult result = service.answer("查看当前设备在线状态", "tester");

        assertThat(result.getContent()).contains("CAM-01", "来源：系统数据");
    }
}
