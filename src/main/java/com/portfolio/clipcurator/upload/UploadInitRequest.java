package com.portfolio.clipcurator.upload;

public record UploadInitRequest(
        String filename,
        String mimeType,
        Long sizeInBytes
) {
}
