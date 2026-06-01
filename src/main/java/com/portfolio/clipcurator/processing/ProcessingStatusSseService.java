package com.portfolio.clipcurator.processing;

import com.portfolio.clipcurator.media.AssetStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ProcessingStatusSseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingStatusSseService.class);
    private static final long EMITTER_TIMEOUT_MILLIS = 10 * 60 * 1000L;
    private static final String EVENT_NAME = "asset-status";

    private final Map<UUID, List<SseEmitter>> emittersByAssetId = new ConcurrentHashMap<>();

    public SseEmitter openStream(UUID mediaAssetId, AssetStatus currentStatus) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);

        emittersByAssetId
                .computeIfAbsent(mediaAssetId, ignored -> new CopyOnWriteArrayList<>())
                .add(emitter);

        emitter.onCompletion(() -> unregisterEmitter(mediaAssetId, emitter));
        emitter.onTimeout(() -> unregisterEmitter(mediaAssetId, emitter));
        emitter.onError(ex -> unregisterEmitter(mediaAssetId, emitter));

        ProcessingStatusEvent currentStatusEvent = new ProcessingStatusEvent(
                mediaAssetId,
                currentStatus,
                Instant.now(),
                "Current processing status"
        );
        sendEvent(mediaAssetId, emitter, currentStatusEvent);

        return emitter;
    }

    public void publishStatus(UUID mediaAssetId, AssetStatus status, String message) {
        ProcessingStatusEvent processingStatusEvent = new ProcessingStatusEvent(
                mediaAssetId,
                status,
                Instant.now(),
                message
        );

        List<SseEmitter> emitters = emittersByAssetId.get(mediaAssetId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            sendEvent(mediaAssetId, emitter, processingStatusEvent);
        }

        if (status == AssetStatus.COMPLETED || status == AssetStatus.FAILED) {
            closeAllEmitters(mediaAssetId);
        }
    }

    private void sendEvent(UUID mediaAssetId, SseEmitter emitter, ProcessingStatusEvent event) {
        try {
            emitter.send(SseEmitter.event().name(EVENT_NAME).data(event));
        } catch (IOException | IllegalStateException ex) {
            unregisterEmitter(mediaAssetId, emitter);
            try {
                emitter.completeWithError(ex);
            } catch (IllegalStateException ignored) {
                LOGGER.debug("Emitter already completed while reporting stream error for mediaAssetId={}", mediaAssetId);
            }
        }
    }

    private void closeAllEmitters(UUID mediaAssetId) {
        List<SseEmitter> emitters = emittersByAssetId.remove(mediaAssetId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                LOGGER.debug("Emitter already completed while closing stream for mediaAssetId={}", mediaAssetId);
            }
        }
    }

    private void unregisterEmitter(UUID mediaAssetId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByAssetId.get(mediaAssetId);
        if (emitters == null) {
            return;
        }

        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByAssetId.remove(mediaAssetId);
        }
    }
}
