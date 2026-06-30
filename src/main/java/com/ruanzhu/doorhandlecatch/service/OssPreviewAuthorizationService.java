package com.ruanzhu.doorhandlecatch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.entity.DetectionTask;
import com.ruanzhu.doorhandlecatch.mapper.DetectionTaskMapper;
import com.ruanzhu.doorhandlecatch.security.DetectionTaskAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OssPreviewAuthorizationService {

    private static final String ALLOWED_PREFIX = "detection/";

    private final DetectionTaskMapper detectionTaskMapper;
    private final ObjectMapper objectMapper;
    private final DetectionTaskAccessPolicy accessPolicy;

    public void authorize(String key, Authentication authentication) {
        if (!StringUtils.hasText(key) || !key.startsWith(ALLOWED_PREFIX)) {
            throw denied();
        }
        LambdaQueryWrapper<DetectionTask> query = new LambdaQueryWrapper<>();
        if (!accessPolicy.isAdmin(authentication)) {
            if (authentication == null || !StringUtils.hasText(authentication.getName())) {
                throw denied();
            }
            query.eq(DetectionTask::getCreatedBy, authentication.getName());
        }
        query.and(candidate -> candidate
                .eq(DetectionTask::getResultJsonOssKey, key)
                .or()
                .like(DetectionTask::getOriginalImageKeysJson, key)
                .or()
                .like(DetectionTask::getPreviewImageKeysJson, key));

        DetectionTask referencedTask = detectionTaskMapper.selectList(query).stream()
                .filter(task -> canAccess(task, authentication))
                .filter(task -> references(task, key))
                .findFirst()
                .orElseThrow(this::denied);
        accessPolicy.assertCanAccess(referencedTask, authentication);
    }

    private boolean canAccess(DetectionTask task, Authentication authentication) {
        return accessPolicy.isAdmin(authentication)
                || authentication != null
                && authentication.getName() != null
                && authentication.getName().equals(task.getCreatedBy());
    }

    private boolean references(DetectionTask task, String key) {
        return key.equals(task.getResultJsonOssKey())
                || readKeys(task.getOriginalImageKeysJson()).contains(key)
                || readKeys(task.getPreviewImageKeysJson()).contains(key);
    }

    private List<String> readKeys(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private BusinessException denied() {
        return new BusinessException(403, "无权访问该资源");
    }
}
