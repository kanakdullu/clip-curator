package com.portfolio.clipcurator.search;

import com.portfolio.clipcurator.ai.AiService;
import com.portfolio.clipcurator.media.AssetStatus;
import com.portfolio.clipcurator.media.MediaAsset;
import com.portfolio.clipcurator.media.MediaAssetRepository;
import com.portfolio.clipcurator.media.Transcript;
import com.portfolio.clipcurator.media.TranscriptRepository;
import com.portfolio.clipcurator.media.VisualFrame;
import com.portfolio.clipcurator.media.VisualFrameRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@SuppressWarnings({"resource", "null"})
class SearchControllerIntegrationTest {

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
    private TranscriptRepository transcriptRepository;

    @Autowired
    private VisualFrameRepository visualFrameRepository;

    @MockitoBean
    private AiService aiService;

    @MockitoBean
    private PineconeVectorService pineconeVectorService;

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        visualFrameRepository.deleteAll();
        transcriptRepository.deleteAll();
        mediaAssetRepository.deleteAll();
    }

    @Test
    void searchShouldHydrateAudioAndVisualMatchesSortedByScore() throws Exception {
        MediaAsset mediaAsset = new MediaAsset(
                null,
                "demo.mp4",
                "s3://clipcurator-dev-bucket/raw/asset/demo.mp4",
                AssetStatus.COMPLETED,
                null
        );
        mediaAssetRepository.save(mediaAsset);

        UUID audioId = UUID.randomUUID();
        UUID visualId = UUID.randomUUID();

        Transcript transcript = new Transcript(
                audioId,
                mediaAsset,
                new BigDecimal("12.500"),
                new BigDecimal("14.100"),
                "We are deploying to AWS."
        );
        transcriptRepository.save(transcript);

        VisualFrame visualFrame = new VisualFrame(
                visualId,
                mediaAsset,
                new BigDecimal("10.000"),
                "s3://clipcurator-dev-bucket/frames/asset/0005.jpg"
        );
        visualFrameRepository.save(visualFrame);

        List<Float> embedding = new ArrayList<>(Collections.nCopies(512, 0.1f));
        when(aiService.getEmbedding("basketball")).thenReturn(embedding);
        when(pineconeVectorService.queryTopMatches(eq(embedding), eq(15), eq(0.25f)))
                .thenReturn(List.of(
                        new PineconeVectorService.VectorMatch(
                                visualId.toString(),
                                0.91f,
                                "visual",
                                mediaAsset.getId().toString()
                        ),
                        new PineconeVectorService.VectorMatch(
                                audioId.toString(),
                                0.87f,
                                "audio",
                                mediaAsset.getId().toString()
                        )
                ));

        when(storageService.generatePresignedGetUrl(mediaAsset.getS3Url()))
                .thenReturn("https://signed.example/video");
        when(storageService.generatePresignedGetUrl(visualFrame.getS3ImageUrl()))
                .thenReturn("https://signed.example/frame");

        mockMvc.perform(get("/api/v1/search").param("q", "basketball"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].mediaAssetId").value(mediaAsset.getId().toString()))
                .andExpect(jsonPath("$[0].matchType").value("visual"))
                .andExpect(jsonPath("$[0].similarityScore").value(0.91))
                .andExpect(jsonPath("$[0].timestamp").value(10.000))
                .andExpect(jsonPath("$[0].contentSnippet").value(nullValue()))
                .andExpect(jsonPath("$[0].s3ThumbnailUrl").value("https://signed.example/frame"))
                .andExpect(jsonPath("$[0].s3VideoUrl").value("https://signed.example/video"))
                .andExpect(jsonPath("$[1].mediaAssetId").value(mediaAsset.getId().toString()))
                .andExpect(jsonPath("$[1].matchType").value("audio"))
                .andExpect(jsonPath("$[1].similarityScore").value(0.87))
                .andExpect(jsonPath("$[1].timestamp").value(12.500))
                .andExpect(jsonPath("$[1].contentSnippet").value("We are deploying to AWS."))
                .andExpect(jsonPath("$[1].s3ThumbnailUrl").value("https://signed.example/video"))
                .andExpect(jsonPath("$[1].s3VideoUrl").value("https://signed.example/video"));
    }

    @Test
    void searchShouldRejectBlankQuery() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", "   "))
                .andExpect(status().isBadRequest());
    }
}
