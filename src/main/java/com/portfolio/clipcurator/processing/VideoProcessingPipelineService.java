package com.portfolio.clipcurator.processing;

import com.portfolio.clipcurator.ai.AiService;
import com.portfolio.clipcurator.ai.TranscriptSegment;
import com.portfolio.clipcurator.media.AssetStatus;
import com.portfolio.clipcurator.media.MediaAsset;
import com.portfolio.clipcurator.media.MediaAssetRepository;
import com.portfolio.clipcurator.media.Transcript;
import com.portfolio.clipcurator.media.TranscriptRepository;
import com.portfolio.clipcurator.media.VisualFrame;
import com.portfolio.clipcurator.media.VisualFrameRepository;
import com.portfolio.clipcurator.storage.StorageService;
import com.portfolio.clipcurator.vector.PineconeVectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class VideoProcessingPipelineService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VideoProcessingPipelineService.class);

    private final MediaAssetRepository mediaAssetRepository;
    private final TranscriptRepository transcriptRepository;
    private final VisualFrameRepository visualFrameRepository;
    private final StorageService storageService;
    private final FfmpegService ffmpegService;
    private final AiService aiService;
    private final PineconeVectorService pineconeVectorService;

    public VideoProcessingPipelineService(
            MediaAssetRepository mediaAssetRepository,
            TranscriptRepository transcriptRepository,
            VisualFrameRepository visualFrameRepository,
            StorageService storageService,
            FfmpegService ffmpegService,
            AiService aiService,
            PineconeVectorService pineconeVectorService
    ) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.transcriptRepository = transcriptRepository;
        this.visualFrameRepository = visualFrameRepository;
        this.storageService = storageService;
        this.ffmpegService = ffmpegService;
        this.aiService = aiService;
        this.pineconeVectorService = pineconeVectorService;
    }

    public void process(UUID mediaAssetId) {
        UUID requiredMediaAssetId = Objects.requireNonNull(mediaAssetId, "mediaAssetId must not be null");
        MediaAsset mediaAsset = mediaAssetRepository.findById(requiredMediaAssetId).orElse(null);
        if (mediaAsset == null) {
            LOGGER.warn("Ignoring processing event because media asset was not found: {}", requiredMediaAssetId);
            return;
        }

        if (mediaAsset.getStatus() == AssetStatus.COMPLETED) {
            LOGGER.info("Ignoring processing event because media asset is already COMPLETED: {}", requiredMediaAssetId);
            return;
        }

        Path workingDirectory = null;
        try {
            if (mediaAsset.getStatus() == AssetStatus.PENDING) {
                mediaAsset.setStatus(AssetStatus.PROCESSING);
                mediaAssetRepository.save(mediaAsset);
            }

            Path localVideo = storageService.downloadVideoToLocal(requiredMediaAssetId, mediaAsset.getS3Url());
            workingDirectory = localVideo.getParent();

            Path audioPath = workingDirectory.resolve("audio.mp3");
            Path framesDirectory = workingDirectory.resolve("output_frames");

            ffmpegService.extractAudio(localVideo, audioPath);
                List<Path> framePaths = ffmpegService.extractFrames(localVideo, framesDirectory);

                List<TranscriptSegment> transcriptSegments = aiService.transcribe(audioPath.toFile());
                int transcriptCount = persistAudioSegments(mediaAsset, transcriptSegments);
                int frameCount = persistVisualFrames(mediaAsset, framePaths);

                mediaAsset.setStatus(AssetStatus.COMPLETED);
                mediaAssetRepository.save(mediaAsset);

                LOGGER.info("Video processing complete for mediaAssetId={} (audioSegments={}, frames={})",
                    mediaAssetId,
                    transcriptCount,
                    frameCount);
        } catch (Exception ex) {
            mediaAsset.setStatus(AssetStatus.FAILED);
            mediaAssetRepository.save(mediaAsset);
            LOGGER.error("Video processing failed for mediaAssetId={}", requiredMediaAssetId, ex);
        } finally {
            if (workingDirectory != null) {
                try {
                    FileSystemUtils.deleteRecursively(workingDirectory);
                } catch (Exception cleanupEx) {
                    LOGGER.warn("Failed to cleanup temp directory for mediaAssetId={}: {}",
                            requiredMediaAssetId,
                            cleanupEx.getMessage());
                }
            }
        }
    }

    private int persistAudioSegments(MediaAsset mediaAsset, List<TranscriptSegment> transcriptSegments) {
        int count = 0;
        for (TranscriptSegment segment : transcriptSegments) {
            UUID transcriptId = UUID.randomUUID();
            Transcript transcript = new Transcript(
                    transcriptId,
                    mediaAsset,
                    segment.startTime(),
                    segment.endTime(),
                    segment.content()
            );
            transcriptRepository.save(transcript);

            List<Float> embedding = aiService.getEmbedding(segment.content());
            pineconeVectorService.upsert(transcriptId.toString(), embedding, "audio", mediaAsset.getId());
            count++;
        }
        return count;
    }

    private int persistVisualFrames(MediaAsset mediaAsset, List<Path> framePaths) {
        int count = 0;
        for (Path framePath : framePaths) {
            UUID frameId = UUID.randomUUID();
            String s3FrameUri = storageService.uploadFrame(mediaAsset.getId(), framePath);
            BigDecimal timestamp = resolveFrameTimestamp(framePath);

            VisualFrame visualFrame = new VisualFrame(
                    frameId,
                    mediaAsset,
                    timestamp,
                    s3FrameUri
            );
            visualFrameRepository.save(visualFrame);

            List<Float> embedding = aiService.getEmbedding(framePath.toFile());
            pineconeVectorService.upsert(frameId.toString(), embedding, "visual", mediaAsset.getId());
            count++;
        }
        return count;
    }

    private BigDecimal resolveFrameTimestamp(Path framePath) {
        String filename = framePath.getFileName().toString();
        int extensionIndex = filename.lastIndexOf('.');
        String frameNumberRaw = extensionIndex > 0 ? filename.substring(0, extensionIndex) : filename;

        try {
            int frameNumber = Integer.parseInt(frameNumberRaw);
            if (frameNumber < 1) {
                throw new IllegalStateException("Frame index must start at 1.");
            }
            return BigDecimal.valueOf((long) (frameNumber - 1) * 2)
                    .setScale(3, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Could not parse frame number from filename: " + filename, ex);
        }
    }
}
