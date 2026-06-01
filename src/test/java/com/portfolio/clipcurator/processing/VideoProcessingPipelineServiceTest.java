package com.portfolio.clipcurator.processing;

import com.portfolio.clipcurator.ai.AiService;
import com.portfolio.clipcurator.ai.TranscriptSegment;
import com.portfolio.clipcurator.media.AssetStatus;
import com.portfolio.clipcurator.media.MediaAsset;
import com.portfolio.clipcurator.media.MediaAssetRepository;
import com.portfolio.clipcurator.media.TranscriptRepository;
import com.portfolio.clipcurator.media.VisualFrame;
import com.portfolio.clipcurator.media.VisualFrameRepository;
import com.portfolio.clipcurator.storage.StorageService;
import com.portfolio.clipcurator.vector.PineconeVectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoProcessingPipelineServiceTest {

    @Mock
    private MediaAssetRepository mediaAssetRepository;

    @Mock
    private TranscriptRepository transcriptRepository;

    @Mock
    private VisualFrameRepository visualFrameRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private FfmpegService ffmpegService;

    @Mock
    private AiService aiService;

    @Mock
    private PineconeVectorService pineconeVectorService;

    @Mock
    private ProcessingStatusSseService processingStatusSseService;

    @InjectMocks
    private VideoProcessingPipelineService videoProcessingPipelineService;

    @Test
    void shouldSkipAudioExtractionWhenNoAudioStreamExists() throws Exception {
        UUID mediaAssetId = UUID.randomUUID();
        MediaAsset mediaAsset = new MediaAsset(
                mediaAssetId,
                "silent-video.mp4",
                "s3://bucket/raw/silent-video.mp4",
                AssetStatus.PENDING,
                Instant.now()
        );

        Path workingDirectory = Files.createTempDirectory("pipeline-silent-video-");
        Path localVideo = Files.createFile(workingDirectory.resolve("silent-video.mp4"));
        Path framePath = workingDirectory.resolve("0001.jpg");

        when(mediaAssetRepository.findById(mediaAssetId)).thenReturn(Optional.of(mediaAsset));
        when(storageService.downloadVideoToLocal(mediaAssetId, mediaAsset.getS3Url())).thenReturn(localVideo);
        when(ffmpegService.extractFrames(eq(localVideo), any(Path.class))).thenReturn(List.of(framePath));
        when(ffmpegService.hasAudioStream(localVideo)).thenReturn(false);
        when(storageService.uploadFrame(mediaAssetId, framePath)).thenReturn("s3://bucket/frames/0001.jpg");
        when(aiService.getEmbedding(framePath.toFile())).thenReturn(List.of(0.1f, 0.2f));

        videoProcessingPipelineService.process(mediaAssetId);

        verify(ffmpegService).hasAudioStream(localVideo);
        verify(ffmpegService, never()).extractAudio(any(Path.class), any(Path.class));
        verify(aiService, never()).transcribe(any());
        verify(visualFrameRepository).save(any(VisualFrame.class));
        verify(pineconeVectorService).upsert(anyString(), anyList(), eq("visual"), eq(mediaAssetId));
        assertEquals(AssetStatus.COMPLETED, mediaAsset.getStatus());
    }

    @Test
    void shouldSkipAudioPipelineForImageAsset() throws Exception {
        UUID mediaAssetId = UUID.randomUUID();
        MediaAsset mediaAsset = new MediaAsset(
                mediaAssetId,
                "poster.jpg",
                "s3://bucket/raw/poster.jpg",
                AssetStatus.PENDING,
                Instant.now()
        );

        Path workingDirectory = Files.createTempDirectory("pipeline-image-");
        Path localImage = Files.createFile(workingDirectory.resolve("poster.jpg"));
        Path framePath = workingDirectory.resolve("0001.jpg");

        when(mediaAssetRepository.findById(mediaAssetId)).thenReturn(Optional.of(mediaAsset));
        when(storageService.downloadVideoToLocal(mediaAssetId, mediaAsset.getS3Url())).thenReturn(localImage);
        when(ffmpegService.extractFrames(eq(localImage), any(Path.class))).thenReturn(List.of(framePath));
        when(storageService.uploadFrame(mediaAssetId, framePath)).thenReturn("s3://bucket/frames/0001.jpg");
        when(aiService.getEmbedding(framePath.toFile())).thenReturn(List.of(0.1f, 0.2f));

        videoProcessingPipelineService.process(mediaAssetId);

        verify(ffmpegService, never()).hasAudioStream(any(Path.class));
        verify(ffmpegService, never()).extractAudio(any(Path.class), any(Path.class));
        verify(aiService, never()).transcribe(any());
        verify(visualFrameRepository).save(any(VisualFrame.class));
        verify(pineconeVectorService).upsert(anyString(), anyList(), eq("visual"), eq(mediaAssetId));
        assertEquals(AssetStatus.COMPLETED, mediaAsset.getStatus());
    }

    @Test
    void shouldTruncateLongTranscriptTextBeforeEmbedding() throws Exception {
        UUID mediaAssetId = UUID.randomUUID();
        MediaAsset mediaAsset = new MediaAsset(
                mediaAssetId,
                "talking-video.mp4",
                "s3://bucket/raw/talking-video.mp4",
                AssetStatus.PENDING,
                Instant.now()
        );

        Path workingDirectory = Files.createTempDirectory("pipeline-long-transcript-");
        Path localVideo = Files.createFile(workingDirectory.resolve("talking-video.mp4"));
        Path framePath = workingDirectory.resolve("0001.jpg");
        String longTranscript = "very long transcript text ".repeat(25);

        when(mediaAssetRepository.findById(mediaAssetId)).thenReturn(Optional.of(mediaAsset));
        when(storageService.downloadVideoToLocal(mediaAssetId, mediaAsset.getS3Url())).thenReturn(localVideo);
        when(ffmpegService.extractFrames(eq(localVideo), any(Path.class))).thenReturn(List.of(framePath));
        when(ffmpegService.hasAudioStream(localVideo)).thenReturn(true);
        when(aiService.transcribe(any())).thenReturn(List.of(new TranscriptSegment(
                BigDecimal.ZERO,
                BigDecimal.ONE,
                longTranscript
        )));
        when(aiService.getEmbedding(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(storageService.uploadFrame(mediaAssetId, framePath)).thenReturn("s3://bucket/frames/0001.jpg");
        when(aiService.getEmbedding(framePath.toFile())).thenReturn(List.of(0.1f, 0.2f));

        videoProcessingPipelineService.process(mediaAssetId);

        ArgumentCaptor<String> embeddingInputCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).getEmbedding(embeddingInputCaptor.capture());

        String embeddingInput = embeddingInputCaptor.getValue();
        assertTrue(embeddingInput.length() <= 150);
        assertTrue(embeddingInput.length() < longTranscript.trim().length());
        assertEquals(AssetStatus.COMPLETED, mediaAsset.getStatus());
    }
}
