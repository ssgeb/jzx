package com.ruanzhu.doorhandlecatch.service.detection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruanzhu.doorhandlecatch.common.BusinessException;
import com.ruanzhu.doorhandlecatch.config.properties.KafkaTaskProperties;
import com.ruanzhu.doorhandlecatch.dto.detection.event.DetectionTaskCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DetectionTaskEventPublisher {

    private final KafkaTemplate<String, DetectionTaskCreatedEvent> kafkaTemplate;
    private final KafkaTaskProperties kafkaTaskProperties;
    private final ObjectMapper objectMapper;

    public void publishCreated(DetectionTaskCreatedEvent event) {
        if (!kafkaTaskProperties.isEnabled()) {
            throw new BusinessException("Kafka 未启用，无法发送检测任务");
        }
        log.info(
                "Publishing detection.task.created event. taskId={}, payload={}",
                event.getTaskId(),
                toJsonForLog(event)
        );
        kafkaTemplate.send(
                kafkaTaskProperties.getTopics().getTaskCreated(),
                event.getTaskId(),
                event
        );
    }

    private String toJsonForLog(DetectionTaskCreatedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize detection.task.created event for logging. taskId={}", event.getTaskId(), ex);
            return "{\"taskId\":\"" + event.getTaskId() + "\",\"serialization\":\"failed\"}";
        }
    }
}
