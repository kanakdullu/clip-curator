package com.portfolio.clipcurator.media;

import com.portfolio.clipcurator.processing.ProcessingStatusSseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/assets")
public class MediaAssetController {

    private final MediaAssetService mediaAssetService;
    private final MediaAssetRepository mediaAssetRepository;
    private final ProcessingStatusSseService processingStatusSseService;

    public MediaAssetController(
            MediaAssetService mediaAssetService,
            MediaAssetRepository mediaAssetRepository,
            ProcessingStatusSseService processingStatusSseService
    ) {
        this.mediaAssetService = mediaAssetService;
        this.mediaAssetRepository = mediaAssetRepository;
        this.processingStatusSseService = processingStatusSseService;
    }

    @GetMapping("/completed")
    public List<CompletedAssetDto> listCompletedAssets(
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return mediaAssetService.listCompletedAssets(limit);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAsset(@PathVariable("id") UUID id) {
        mediaAssetService.deleteAsset(id);
    }

    @GetMapping(value = "/{id}/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAssetStatus(@PathVariable("id") UUID id) {
        MediaAsset mediaAsset = mediaAssetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found."));

        return processingStatusSseService.openStream(mediaAsset.getId(), mediaAsset.getStatus());
    }
}
