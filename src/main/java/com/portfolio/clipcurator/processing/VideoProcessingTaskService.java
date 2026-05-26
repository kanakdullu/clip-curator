package com.portfolio.clipcurator.processing;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class VideoProcessingTaskService {

    private final VideoProcessingPipelineService videoProcessingPipelineService;

    public VideoProcessingTaskService(VideoProcessingPipelineService videoProcessingPipelineService) {
        this.videoProcessingPipelineService = videoProcessingPipelineService;
    }

    @Async("videoWorkerExecutor")
    public void processAsync(UUID mediaAssetId) {
        videoProcessingPipelineService.process(mediaAssetId);
    }
}
