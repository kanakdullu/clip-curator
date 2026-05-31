package com.portfolio.clipcurator.media;

import com.portfolio.clipcurator.storage.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class MediaAssetService {

    private static final int DEFAULT_LIMIT = 24;
    private static final int MAX_LIMIT = 100;

    private final MediaAssetRepository mediaAssetRepository;
    private final TranscriptRepository transcriptRepository;
    private final VisualFrameRepository visualFrameRepository;
    private final StorageService storageService;

    public MediaAssetService(
            MediaAssetRepository mediaAssetRepository,
            TranscriptRepository transcriptRepository,
            VisualFrameRepository visualFrameRepository,
            StorageService storageService
    ) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.transcriptRepository = transcriptRepository;
        this.visualFrameRepository = visualFrameRepository;
        this.storageService = storageService;
    }

    public List<CompletedAssetDto> listCompletedAssets(Integer limit) {
        int normalizedLimit = normalizeLimit(limit);

        return mediaAssetRepository.findByStatusOrderByCreatedAtDesc(AssetStatus.COMPLETED).stream()
                .limit(normalizedLimit)
                .map(this::toCompletedAssetDto)
                .toList();
    }

    @Transactional
    public void deleteAsset(UUID mediaAssetId) {
        UUID requiredMediaAssetId = Objects.requireNonNull(mediaAssetId, "mediaAssetId must not be null");

        MediaAsset mediaAsset = mediaAssetRepository.findById(requiredMediaAssetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found."));

        transcriptRepository.deleteByMediaAsset_Id(requiredMediaAssetId);
        visualFrameRepository.deleteByMediaAsset_Id(requiredMediaAssetId);
        mediaAssetRepository.delete(mediaAsset);
    }

    private CompletedAssetDto toCompletedAssetDto(MediaAsset mediaAsset) {
        String videoUrl = storageService.generatePresignedGetUrl(mediaAsset.getS3Url());

        String thumbnailUrl = visualFrameRepository.findFirstByMediaAsset_IdOrderByTimestampAsc(mediaAsset.getId())
                .map(VisualFrame::getS3ImageUrl)
                .map(storageService::generatePresignedGetUrl)
                .orElse(null);

        return new CompletedAssetDto(
                mediaAsset.getId(),
                mediaAsset.getFilename(),
                mediaAsset.getCreatedAt(),
                thumbnailUrl,
                videoUrl
        );
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        if (limit < 1) {
            return 1;
        }

        return Math.min(limit, MAX_LIMIT);
    }
}
