package com.ruanzhu.doorhandlecatch.service.detection;

import com.ruanzhu.doorhandlecatch.dto.detection.event.DetectionTaskFinishedEvent;
import com.ruanzhu.doorhandlecatch.service.impl.DetectionTaskServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DetectionTaskFinishedEventListener {

    private final DetectionTaskServiceImpl detectionTaskService;

    @KafkaListener(
            topics = "#{@kafkaTaskProperties.topics.taskFinished}",
            groupId = "#{@kafkaTaskProperties.consumerGroup}",
            containerFactory = "detectionTaskFinishedKafkaListenerContainerFactory"
    )
    public void onFinished(DetectionTaskFinishedEvent event) {
        detectionTaskService.applyFinishedEvent(event);
    }
}
