package com.ruanzhu.doorhandlecatch.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.config.properties.OssProperties;
import com.ruanzhu.doorhandlecatch.mapper.DetectionTaskMapper;
import com.ruanzhu.doorhandlecatch.mapper.ModelInfoMapper;
import com.ruanzhu.doorhandlecatch.security.DetectionTaskAccessPolicy;
import com.ruanzhu.doorhandlecatch.service.ChatSessionService;
import com.ruanzhu.doorhandlecatch.service.DetectionTaskDispatchService;
import com.ruanzhu.doorhandlecatch.service.ModelService;
import com.ruanzhu.doorhandlecatch.service.OssStorageService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.mock;

final class DetectionTaskServiceFixture {

    final DetectionTaskMapper detectionTaskMapper = mock(DetectionTaskMapper.class);
    final ModelInfoMapper modelInfoMapper = mock(ModelInfoMapper.class);
    final ModelService modelService = mock(ModelService.class);
    final OssStorageService ossStorageService = mock(OssStorageService.class);
    final DetectionTaskDispatchService detectionTaskDispatchService = mock(DetectionTaskDispatchService.class);
    final ChatSessionService chatSessionService = mock(ChatSessionService.class);

    DetectionTaskServiceImpl service;

    void setUp() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );

        OssProperties ossProperties = new OssProperties();
        ossProperties.setUploadUrlExpireMinutes(15);
        ossProperties.setPreviewUrlExpireMinutes(30);
        ossProperties.setBasePrefix("detection");

        service = new DetectionTaskServiceImpl(
                detectionTaskMapper,
                modelInfoMapper,
                modelService,
                ossStorageService,
                ossProperties,
                detectionTaskDispatchService,
                chatSessionService,
                new ObjectMapper(),
                new DetectionTaskAccessPolicy()
        );
        ReflectionTestUtils.setField(service, "maxImagesPerBatch", 200);
        ReflectionTestUtils.setField(service, "maxImageBytes", 10L * 1024L * 1024L);
    }

    void tearDown() {
        SecurityContextHolder.clearContext();
    }
}
