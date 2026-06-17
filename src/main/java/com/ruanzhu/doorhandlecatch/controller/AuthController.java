package com.ruanzhu.doorhandlecatch.controller;

import com.ruanzhu.doorhandlecatch.common.Result;
import com.ruanzhu.doorhandlecatch.dto.LoginRequest;
import com.ruanzhu.doorhandlecatch.dto.LoginResponse;
import com.ruanzhu.doorhandlecatch.security.LoginRateLimiter;
import com.ruanzhu.doorhandlecatch.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    public static final String AUTH_COOKIE_NAME = "DOOR_HANDLE_TOKEN";

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    @Value("${jwt.expiration:86400}")
    private Long jwtExpirationSeconds;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request,
                                   HttpServletRequest httpRequest,
                                   HttpServletResponse httpResponse) {
        String clientIp = getClientIp(httpRequest);

        // 检查速率限制
        if (!loginRateLimiter.isAllowed(clientIp)) {
            long waitSeconds = loginRateLimiter.getRemainingWaitSeconds(clientIp);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("code", 429);
            errorResponse.put("message", "登录尝试次数过多，请 " + waitSeconds + " 秒后再试");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorResponse);
        }

        try {
            LoginResponse response = authService.login(request);
            // 登录成功，清除失败记录
            loginRateLimiter.recordSuccess(clientIp);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, buildAuthCookie(response.getToken(), jwtExpirationSeconds).toString())
                    .body(Result.success(response));
        } catch (Exception e) {
            // 登录失败，记录尝试
            loginRateLimiter.recordFailedAttempt(clientIp);
            throw e;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Result<Void>> logout() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, buildAuthCookie("", 0L).toString())
                .body(Result.success(null));
    }

    @GetMapping("/check")
    public Result<Map<String, Object>> checkAuth() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();

        Map<String, Object> response = new HashMap<>();
        response.put("authenticated", true);
        response.put("username", username);

        return Result.success(response);
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_FORWARDED_FOR");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_FORWARDED");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多个代理时取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    private ResponseCookie buildAuthCookie(String token, Long maxAgeSeconds) {
        return ResponseCookie.from(AUTH_COOKIE_NAME, token == null ? "" : token)
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(maxAgeSeconds == null ? 0L : maxAgeSeconds)
                .sameSite("Lax")
                .build();
    }
} 
