package com.ruanzhu.doorhandlecatch.security;

import com.ruanzhu.doorhandlecatch.config.properties.ChatAssistantProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InternalRequestSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final ChatAssistantProperties properties;
    private final Map<String, Long> claimedNonces = new ConcurrentHashMap<>();

    public InternalRequestSigner(ChatAssistantProperties properties) {
        this.properties = properties;
    }

    public String sign(String method, String path, String timestamp, String nonce, byte[] body) {
        String secret = requireSecret();
        String bodyHash = HexFormat.of().formatHex(sha256(body));
        String canonical = String.join("\n", method.toUpperCase(), path, timestamp, nonce, bodyHash);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("生成内部请求签名失败", ex);
        }
    }

    public void verify(String method, String path, String timestamp, String nonce,
                       String suppliedSignature, byte[] body) {
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)
                || !StringUtils.hasText(suppliedSignature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部请求签名不完整");
        }
        long requestEpoch;
        try {
            requestEpoch = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部请求时间戳无效");
        }
        long now = Instant.now().getEpochSecond();
        int maxSkew = Math.max(properties.getInternalSignatureMaxSkewSeconds(), 1);
        if (Math.abs(now - requestEpoch) > maxSkew) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部请求已过期");
        }
        String expected = sign(method, path, timestamp, nonce, body);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                suppliedSignature.getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部请求签名无效");
        }
        long expiresAt = now + Math.max(maxSkew * 2L, 60L);
        claimedNonces.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (claimedNonces.putIfAbsent(nonce, expiresAt) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "检测到重复内部请求");
        }
    }

    private String requireSecret() {
        if (!StringUtils.hasText(properties.getInternalHmacSecret())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "内部 HMAC 密钥未配置");
        }
        return properties.getInternalHmacSecret();
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception ex) {
            throw new IllegalStateException("计算请求摘要失败", ex);
        }
    }
}
