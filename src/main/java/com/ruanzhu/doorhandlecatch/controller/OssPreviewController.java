package com.ruanzhu.doorhandlecatch.controller;

import com.ruanzhu.doorhandlecatch.config.properties.OssProperties;
import com.ruanzhu.doorhandlecatch.service.OssStorageService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * OSS 图片预览代理 — 每次请求生成新的预签名 URL 并 302 重定向。
 * 解决预签名 URL 过期导致检测记录图片无法加载的问题。
 */
@RestController
@RequestMapping("/api/oss")
@RequiredArgsConstructor
@Slf4j
public class OssPreviewController {

    private final OssStorageService ossStorageService;
    private final OssProperties ossProperties;

    // 允许的 key 前缀白名单
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "detection/"  // 检测相关的文件
    );

    @GetMapping("/preview")
    public void preview(@RequestParam String key, HttpServletResponse response) throws IOException {
        if (!StringUtils.hasText(key)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "缺少 key 参数");
            return;
        }

        // 校验 key 前缀，防止访问其他文件
        if (!isAllowedKey(key)) {
            log.warn("OSS 预览请求被拒绝，key 不在白名单中: {}", key);
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "无权访问该文件");
            return;
        }

        if (!ossStorageService.isConfigured()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "OSS 未配置");
            return;
        }

        try {
            var url = ossStorageService.generateGetUrl(
                    key,
                    Duration.ofMinutes(ossProperties.getPreviewUrlExpireMinutes())
            );
            response.sendRedirect(url.toString());
        } catch (Exception e) {
            log.error("OSS 预览重定向失败: key={}", key, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "OSS 预览失败");
        }
    }

    /**
     * 校验 key 是否在允许的前缀白名单中
     */
    private boolean isAllowedKey(String key) {
        return ALLOWED_PREFIXES.stream().anyMatch(key::startsWith);
    }
}
