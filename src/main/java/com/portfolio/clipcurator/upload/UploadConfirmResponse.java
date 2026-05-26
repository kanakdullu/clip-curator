package com.portfolio.clipcurator.upload;

import com.portfolio.clipcurator.media.AssetStatus;

import java.util.UUID;

public record UploadConfirmResponse(
        UUID mediaAssetId,
        AssetStatus status,
        String message
) {
}
