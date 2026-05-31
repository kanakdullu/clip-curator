package com.portfolio.clipcurator.media;

import java.time.Instant;
import java.util.UUID;

public record CompletedAssetDto(
        UUID mediaAssetId,
        String filename,
        Instant createdAt,
        String s3ThumbnailUrl,
        String s3VideoUrl
) {
}
