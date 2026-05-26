package com.portfolio.clipcurator.storage;

public record PresignedUpload(
        String uploadUrl,
        String objectKey,
        String s3Uri
) {
}
