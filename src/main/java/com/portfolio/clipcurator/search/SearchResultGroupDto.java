package com.portfolio.clipcurator.search;

import java.util.UUID;

public record SearchResultGroupDto(
        UUID mediaAssetId,
        float bestSimilarityScore,
        String s3VideoUrl,
        SearchResultDto bestAudioMatch,
        SearchResultDto bestVisualMatch
) {
}