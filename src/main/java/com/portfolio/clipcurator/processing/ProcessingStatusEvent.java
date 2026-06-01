package com.portfolio.clipcurator.processing;

import com.portfolio.clipcurator.media.AssetStatus;

import java.time.Instant;
import java.util.UUID;

public record ProcessingStatusEvent(
        UUID mediaAssetId,
        AssetStatus status,
        Instant timestamp,
        String message
) {
}
