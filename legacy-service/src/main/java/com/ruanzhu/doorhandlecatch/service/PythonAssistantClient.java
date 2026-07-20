package com.ruanzhu.doorhandlecatch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.config.properties.ChatAssistantProperties;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonAgentInvokeRequest;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonAgentResponse;
import com.ruanzhu.doorhandlecatch.dto.internal.PythonAgentResumeRequest;
import com.ruanzhu.doorhandlecatch.security.InternalRequestSigner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Component
public class PythonAssistantClient {

    private final ChatAssistantProperties properties;
    private final ObjectMapper objectMapper;
    private final InternalRequestSigner signer;
    private final HttpClient httpClient;

    public PythonAssistantClient(ChatAssistantProperties properties,
                                 ObjectMapper objectMapper,
                                 InternalRequestSigner signer) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.signer = signer;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getPythonConnectTimeoutMs()))
                .build();
    }

    public PythonAgentResponse invoke(PythonAgentInvokeRequest request) {
        return post("/internal/v1/agent/invoke", request);
    }

    public PythonAgentResponse resume(PythonAgentResumeRequest request) {
        return post("/internal/v1/agent/resume", request);
    }

    private PythonAgentResponse post(String path, Object request) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString();
            String signature = signer.sign("POST", path, timestamp, nonce, body);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getPythonBaseUrl().replaceAll("/+$", "") + path))
                    .timeout(Duration.ofMillis(properties.getPythonReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("X-Timestamp", timestamp)
                    .header("X-Nonce", nonce)
                    .header("X-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("Python 智能体响应异常，状态码=" + response.statusCode());
            }
            return objectMapper.readValue(response.body(), PythonAgentResponse.class);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("调用 Python 智能体被中断");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("调用 Python 智能体失败: " + ex.getMessage());
        }
    }
}
