package com.portfolio.clipcurator.upload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.clipcurator.config.ProcessingConfig;
import com.portfolio.clipcurator.media.AssetStatus;
import com.portfolio.clipcurator.media.MediaAsset;
import com.portfolio.clipcurator.media.MediaAssetRepository;
import com.portfolio.clipcurator.storage.PresignedUpload;
import com.portfolio.clipcurator.storage.StorageService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class UploadService {

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("video/mp4", "video/quicktime");
    private static final long MAX_FILE_SIZE_BYTES = 2_147_483_648L;

    private final MediaAssetRepository mediaAssetRepository;
    private final StorageService storageService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public UploadService(
            MediaAssetRepository mediaAssetRepository,
            StorageService storageService,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper
    ) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.storageService = storageService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UploadInitResponse initUpload(UploadInitRequest request) {
        validateInitRequest(request);

        MediaAsset mediaAsset = new MediaAsset(
            null,
            request.filename().trim(),
            "pending://uninitialized",
            AssetStatus.PENDING,
            null
        );
        mediaAssetRepository.save(mediaAsset);

        PresignedUpload presignedUpload = storageService.generateUploadUrl(
                mediaAsset.getId(),
                request.filename(),
                request.mimeType().toLowerCase(Locale.ROOT)
        );

        mediaAsset.setS3Url(presignedUpload.s3Uri());

        return new UploadInitResponse(mediaAsset.getId(), presignedUpload.uploadUrl());
    }

    @Transactional
    public UploadConfirmResponse confirmUpload(UUID mediaAssetId) {
        UUID requiredMediaAssetId = Objects.requireNonNull(mediaAssetId, "mediaAssetId must not be null");

        MediaAsset mediaAsset = mediaAssetRepository.findById(requiredMediaAssetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media asset not found."));

        if (mediaAsset.getStatus() == AssetStatus.PROCESSING || mediaAsset.getStatus() == AssetStatus.COMPLETED) {
            return new UploadConfirmResponse(
                    mediaAsset.getId(),
                    mediaAsset.getStatus(),
                    "Upload already confirmed. No additional queue event published."
            );
        }

        if (mediaAsset.getStatus() == AssetStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Asset is FAILED. Use retry endpoint.");
        }

        mediaAsset.setStatus(AssetStatus.PROCESSING);
        publishProcessVideoEvent(mediaAsset.getId());

        return new UploadConfirmResponse(
                mediaAsset.getId(),
                mediaAsset.getStatus(),
                "Upload confirmed and processing queued."
        );
    }

    private void validateInitRequest(UploadInitRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required.");
        }

        if (request.filename() == null || request.filename().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filename is required.");
        }

        if (request.mimeType() == null || request.mimeType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mimeType is required.");
        }

        String normalizedMimeType = request.mimeType().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MIME_TYPES.contains(normalizedMimeType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only video/mp4 or video/quicktime is supported.");
        }

        if (request.sizeInBytes() == null || request.sizeInBytes() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sizeInBytes must be greater than 0.");
        }

        if (request.sizeInBytes() > MAX_FILE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Maximum upload size is 2147483648 bytes (2GB).");
        }
    }

    private void publishProcessVideoEvent(UUID mediaAssetId) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of("mediaAssetId", mediaAssetId.toString()));
            stringRedisTemplate.convertAndSend(ProcessingConfig.VIDEO_PROCESSING_TOPIC, Objects.requireNonNull(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize processing event payload.", ex);
        }
    }
}
