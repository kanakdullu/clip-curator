package com.portfolio.clipcurator.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.clipcurator.ai.AiService;
import com.portfolio.clipcurator.media.AssetStatus;
import com.portfolio.clipcurator.media.MediaAsset;
import com.portfolio.clipcurator.media.MediaAssetRepository;
import com.portfolio.clipcurator.storage.PresignedUpload;
import com.portfolio.clipcurator.storage.StorageService;
import com.portfolio.clipcurator.vector.PineconeVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@SuppressWarnings({"resource", "null"})
class UploadControllerIntegrationTest {

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
    private ObjectMapper objectMapper;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

        @MockitoBean
    private StorageService storageService;

        @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

        @MockitoBean
        private AiService aiService;

        @MockitoBean
        private PineconeVectorService pineconeVectorService;

    @BeforeEach
    void setUp() {
        mediaAssetRepository.deleteAll();
    }

    @Test
    void initUploadShouldCreatePendingAssetAndReturnPresignedUrl() throws Exception {
        when(storageService.generateUploadUrl(any(UUID.class), eq("demo clip.mp4"), eq("video/mp4")))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    return new PresignedUpload(
                            "https://example.com/upload/" + id,
                            "raw/" + id + "/demo_clip.mp4",
                            "s3://clipcurator-dev-bucket/raw/" + id + "/demo_clip.mp4"
                    );
                });

        String requestBody = """
                {
                  "filename": "demo clip.mp4",
                  "mimeType": "video/mp4",
                  "sizeInBytes": 1024
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/upload/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaAssetId").isNotEmpty())
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID mediaAssetId = UUID.fromString(response.get("mediaAssetId").asText());

        MediaAsset savedAsset = mediaAssetRepository.findById(mediaAssetId).orElseThrow();
        assertEquals(AssetStatus.PENDING, savedAsset.getStatus());
        assertTrue(savedAsset.getS3Url().startsWith("s3://clipcurator-dev-bucket/raw/" + mediaAssetId + "/"));
    }

    @Test
    void initUploadShouldRejectUnsupportedMimeType() throws Exception {
        String requestBody = """
                {
                  "filename": "demo.avi",
                  "mimeType": "video/x-msvideo",
                  "sizeInBytes": 1024
                }
                """;

        mockMvc.perform(post("/api/v1/upload/init")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void confirmUploadShouldSetProcessingAndPublishRedisEvent() throws Exception {
        MediaAsset asset = new MediaAsset(
                null,
                "video.mp4",
                "s3://clipcurator-dev-bucket/raw/temp/video.mp4",
                AssetStatus.PENDING,
                null
        );
        mediaAssetRepository.save(asset);

        mockMvc.perform(post("/api/v1/upload/confirm/{id}", asset.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaAssetId").value(asset.getId().toString()))
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(eq("video-processing-queue"), payloadCaptor.capture());

        JsonNode payload = objectMapper.readTree(payloadCaptor.getValue());
        assertEquals(asset.getId().toString(), payload.get("mediaAssetId").asText());

        MediaAsset updated = mediaAssetRepository.findById(asset.getId()).orElseThrow();
        assertEquals(AssetStatus.PROCESSING, updated.getStatus());
    }

    @Test
    void confirmUploadShouldBeIdempotentForProcessingAsset() throws Exception {
        MediaAsset asset = new MediaAsset(
                null,
                "video.mp4",
                "s3://clipcurator-dev-bucket/raw/temp/video.mp4",
                AssetStatus.PROCESSING,
                null
        );
        mediaAssetRepository.save(asset);

        mockMvc.perform(post("/api/v1/upload/confirm/{id}", asset.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
    }
}
