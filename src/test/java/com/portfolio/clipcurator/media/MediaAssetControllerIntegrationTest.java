package com.portfolio.clipcurator.media;

import com.portfolio.clipcurator.ai.AiService;
import com.portfolio.clipcurator.storage.StorageService;
import com.portfolio.clipcurator.vector.PineconeVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@SuppressWarnings({"resource", "null"})
class MediaAssetControllerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("clipcurator_test")
            .withUsername("clipcurator")
            .withPassword("clipcurator");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private VisualFrameRepository visualFrameRepository;

        @Autowired
        private TranscriptRepository transcriptRepository;

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private AiService aiService;

    @MockitoBean
    private PineconeVectorService pineconeVectorService;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        visualFrameRepository.deleteAll();
                transcriptRepository.deleteAll();
        mediaAssetRepository.deleteAll();
    }

    @Test
    void completedAssetsEndpointShouldReturnCompletedAssetsWithSignedUrls() throws Exception {
        MediaAsset olderCompleted = mediaAssetRepository.save(new MediaAsset(
                null,
                "older.mp4",
                "s3://bucket/raw/older.mp4",
                AssetStatus.COMPLETED,
                Instant.parse("2025-01-01T10:00:00Z")
        ));

        MediaAsset newerCompleted = mediaAssetRepository.save(new MediaAsset(
                null,
                "newer.mp4",
                "s3://bucket/raw/newer.mp4",
                AssetStatus.COMPLETED,
                Instant.parse("2025-01-02T10:00:00Z")
        ));

        mediaAssetRepository.save(new MediaAsset(
                null,
                "processing.mp4",
                "s3://bucket/raw/processing.mp4",
                AssetStatus.PROCESSING,
                Instant.parse("2025-01-03T10:00:00Z")
        ));

        VisualFrame frame = visualFrameRepository.save(new VisualFrame(
                UUID.randomUUID(),
                newerCompleted,
                new BigDecimal("2.0"),
                "s3://bucket/frames/newer-0001.jpg"
        ));

        when(storageService.generatePresignedGetUrl(olderCompleted.getS3Url()))
                .thenReturn("https://signed.example/older-video");
        when(storageService.generatePresignedGetUrl(newerCompleted.getS3Url()))
                .thenReturn("https://signed.example/newer-video");
        when(storageService.generatePresignedGetUrl(frame.getS3ImageUrl()))
                .thenReturn("https://signed.example/newer-thumb");

        mockMvc.perform(get("/api/v1/assets/completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].mediaAssetId").value(newerCompleted.getId().toString()))
                .andExpect(jsonPath("$[0].filename").value("newer.mp4"))
                .andExpect(jsonPath("$[0].s3ThumbnailUrl").value("https://signed.example/newer-thumb"))
                .andExpect(jsonPath("$[0].s3VideoUrl").value("https://signed.example/newer-video"))
                .andExpect(jsonPath("$[1].mediaAssetId").value(olderCompleted.getId().toString()))
                .andExpect(jsonPath("$[1].filename").value("older.mp4"))
                .andExpect(jsonPath("$[1].s3ThumbnailUrl").value(nullValue()))
                .andExpect(jsonPath("$[1].s3VideoUrl").value("https://signed.example/older-video"));
    }

    @Test
    void completedAssetsEndpointShouldApplyLimit() throws Exception {
        MediaAsset first = mediaAssetRepository.save(new MediaAsset(
                null,
                "first.mp4",
                "s3://bucket/raw/first.mp4",
                AssetStatus.COMPLETED,
                Instant.parse("2025-01-01T10:00:00Z")
        ));

        MediaAsset second = mediaAssetRepository.save(new MediaAsset(
                null,
                "second.mp4",
                "s3://bucket/raw/second.mp4",
                AssetStatus.COMPLETED,
                Instant.parse("2025-01-02T10:00:00Z")
        ));

        when(storageService.generatePresignedGetUrl(first.getS3Url()))
                .thenReturn("https://signed.example/first-video");
        when(storageService.generatePresignedGetUrl(second.getS3Url()))
                .thenReturn("https://signed.example/second-video");

        mockMvc.perform(get("/api/v1/assets/completed").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].mediaAssetId").value(second.getId().toString()));
    }

    @Test
    void deleteAssetEndpointShouldDeleteAssetAndDerivedRows() throws Exception {
        MediaAsset mediaAsset = mediaAssetRepository.save(new MediaAsset(
                null,
                "delete-me.mp4",
                "s3://bucket/raw/delete-me.mp4",
                AssetStatus.COMPLETED,
                Instant.parse("2025-01-01T10:00:00Z")
        ));

        transcriptRepository.save(new Transcript(
                UUID.randomUUID(),
                mediaAsset,
                new BigDecimal("1.000"),
                new BigDecimal("3.000"),
                "Delete this transcript"
        ));

        visualFrameRepository.save(new VisualFrame(
                UUID.randomUUID(),
                mediaAsset,
                new BigDecimal("2.000"),
                "s3://bucket/frames/delete-me-0001.jpg"
        ));

        mockMvc.perform(delete("/api/v1/assets/{id}", mediaAsset.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/assets/completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleteAssetEndpointShouldReturnNotFoundForUnknownAsset() throws Exception {
        mockMvc.perform(delete("/api/v1/assets/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
