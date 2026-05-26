package com.portfolio.clipcurator.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class VideoProcessingWorker implements MessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoProcessingWorker.class);

    private final ObjectMapper objectMapper;
    private final VideoProcessingTaskService videoProcessingTaskService;

    public VideoProcessingWorker(ObjectMapper objectMapper, VideoProcessingTaskService videoProcessingTaskService) {
        this.objectMapper = objectMapper;
        this.videoProcessingTaskService = videoProcessingTaskService;
    }

    @Override
    public void onMessage(@NonNull Message message, @Nullable byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        try {
            ProcessVideoEvent processVideoEvent = objectMapper.readValue(payload, ProcessVideoEvent.class);
            videoProcessingTaskService.processAsync(processVideoEvent.mediaAssetId());
        } catch (Exception ex) {
            LOGGER.error("Failed to process video event payload: {}", payload, ex);
        }
    }
}
