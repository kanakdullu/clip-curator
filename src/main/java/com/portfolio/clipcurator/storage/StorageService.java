package com.portfolio.clipcurator.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.io.IOException;
import java.util.UUID;

@Service
public class StorageService {

    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);
    private static final Duration DOWNLOAD_URL_TTL = Duration.ofMinutes(15);

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final String bucketName;
    private final String tempAssetsDirectory;

    public StorageService(
            S3Presigner s3Presigner,
            S3Client s3Client,
            @Value("${app.aws.s3.bucket}") String bucketName,
            @Value("${app.processing.tmp-dir:/tmp/clipcurator/assets}") String tempAssetsDirectory
    ) {
        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.tempAssetsDirectory = tempAssetsDirectory;
    }

    public PresignedUpload generateUploadUrl(UUID mediaAssetId, String filename, String mimeType) {
        String sanitizedFilename = sanitizeFilename(filename);
        String objectKey = "raw/" + mediaAssetId + "/" + sanitizedFilename;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(mimeType)
                .build();

        PutObjectPresignRequest putObjectPresignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(UPLOAD_URL_TTL)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(putObjectPresignRequest);
        String s3Uri = "s3://" + bucketName + "/" + objectKey;

        return new PresignedUpload(presignedRequest.url().toString(), objectKey, s3Uri);
    }

    public Path downloadVideoToLocal(UUID mediaAssetId, String s3Uri) {
        S3Location s3Location = parseS3Uri(s3Uri);

        String filename = Path.of(s3Location.objectKey()).getFileName().toString();
        if (filename.isBlank()) {
            throw new IllegalArgumentException("S3 URI does not contain a valid filename: " + s3Uri);
        }

        Path assetDirectory = Path.of(tempAssetsDirectory).resolve(mediaAssetId.toString());
        try {
            Files.createDirectories(assetDirectory);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create local temp directory for media asset.", ex);
        }

        Path localVideoPath = assetDirectory.resolve(filename);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Location.bucket())
                .key(s3Location.objectKey())
                .build();

        s3Client.getObject(getObjectRequest, ResponseTransformer.toFile(localVideoPath));
        return localVideoPath;
    }

    public String uploadFrame(UUID mediaAssetId, Path framePath) {
        if (framePath == null || !Files.exists(framePath)) {
            throw new IllegalArgumentException("Frame file does not exist: " + framePath);
        }

        String frameFilename = framePath.getFileName().toString();
        String objectKey = "frames/" + mediaAssetId + "/" + frameFilename;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType("image/jpeg")
                .build();

        try {
            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(Files.readAllBytes(framePath)));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read frame bytes for S3 upload.", ex);
        }

        return "s3://" + bucketName + "/" + objectKey;
    }

    public String generatePresignedGetUrl(String s3Uri) {
        S3Location s3Location = parseS3Uri(s3Uri);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Location.bucket())
                .key(s3Location.objectKey())
                .build();

        GetObjectPresignRequest getObjectPresignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(DOWNLOAD_URL_TTL)
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presignedGetObjectRequest = s3Presigner.presignGetObject(getObjectPresignRequest);
        return presignedGetObjectRequest.url().toString();
    }

    public void deleteObject(String s3Uri) {
        S3Location s3Location = parseS3Uri(s3Uri);

        DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                .bucket(s3Location.bucket())
                .key(s3Location.objectKey())
                .build();

        s3Client.deleteObject(deleteObjectRequest);
    }

    private S3Location parseS3Uri(String s3Uri) {
        if (s3Uri == null || s3Uri.isBlank()) {
            throw new IllegalArgumentException("S3 URI cannot be empty.");
        }

        URI uri = URI.create(s3Uri);
        if (!"s3".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Invalid S3 URI format: " + s3Uri);
        }

        String key = uri.getPath();
        if (key == null || key.isBlank() || "/".equals(key)) {
            throw new IllegalArgumentException("S3 URI is missing object key: " + s3Uri);
        }

        return new S3Location(uri.getHost(), key.startsWith("/") ? key.substring(1) : key);
    }

    private record S3Location(String bucket, String objectKey) {
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename cannot be empty.");
        }

        String justFilename = filename.trim().replace("\\", "/");
        justFilename = justFilename.substring(justFilename.lastIndexOf('/') + 1);
        justFilename = justFilename.replaceAll("[^A-Za-z0-9._-]", "_");

        if (justFilename.isBlank()) {
            throw new IllegalArgumentException("Filename cannot be empty after sanitization.");
        }

        return justFilename;
    }
}
