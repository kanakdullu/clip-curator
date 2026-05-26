package com.portfolio.clipcurator.search;

import java.math.BigDecimal;
import java.util.UUID;

public record SearchResultDto(
        UUID mediaAssetId,
        String matchType,
        float similarityScore,
        BigDecimal timestamp,
        String contentSnippet,
        String s3ThumbnailUrl,
        String s3VideoUrl
) {
}
