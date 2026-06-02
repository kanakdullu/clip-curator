package com.portfolio.clipcurator.media;

import com.portfolio.clipcurator.storage.StorageService;
import com.portfolio.clipcurator.vector.PineconeVectorService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
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
    private final PineconeVectorService pineconeVectorService;

    public MediaAssetService(
            MediaAssetRepository mediaAssetRepository,
            TranscriptRepository transcriptRepository,
            VisualFrameRepository visualFrameRepository,
            StorageService storageService,
            PineconeVectorService pineconeVectorService
    ) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.transcriptRepository = transcriptRepository;
        this.visualFrameRepository = visualFrameRepository;
        this.storageService = storageService;
        this.pineconeVectorService = pineconeVectorService;
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

        List<Transcript> transcripts = transcriptRepository.findByMediaAsset_Id(requiredMediaAssetId);
        List<VisualFrame> visualFrames = visualFrameRepository.findByMediaAsset_Id(requiredMediaAssetId);

        List<String> vectorIds = new ArrayList<>(transcripts.size() + visualFrames.size());
        for (Transcript transcript : transcripts) {
            vectorIds.add(transcript.getId().toString());
        }
        for (VisualFrame visualFrame : visualFrames) {
            vectorIds.add(visualFrame.getId().toString());
        }

        if (!vectorIds.isEmpty()) {
            pineconeVectorService.deleteByIds(vectorIds);
        }

        storageService.deleteObject(mediaAsset.getS3Url());
        for (VisualFrame visualFrame : visualFrames) {
            storageService.deleteObject(visualFrame.getS3ImageUrl());
        }

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
