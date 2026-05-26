package com.portfolio.clipcurator.upload;

import java.util.UUID;

public record UploadInitResponse(
        UUID mediaAssetId,
        String uploadUrl
) {
}
