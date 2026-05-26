package com.portfolio.clipcurator.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.clipcurator.ai.AiService;
import com.portfolio.clipcurator.vector.PineconeVectorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Objects;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
@SuppressWarnings("resource")
class VideoProcessingWorkerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("clipcurator_test")
            .withUsername("clipcurator")
            .withPassword("clipcurator");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VideoProcessingTaskService videoProcessingTaskService;

    @MockitoBean
    private AiService aiService;

    @MockitoBean
    private PineconeVectorService pineconeVectorService;

    @Test
    void shouldConsumeRedisEventAndDispatchAsyncProcessing() throws Exception {
        UUID mediaAssetId = UUID.randomUUID();
        String payload = objectMapper.writeValueAsString(new ProcessVideoEvent(mediaAssetId));

        stringRedisTemplate.convertAndSend("video-processing-queue", Objects.requireNonNull(payload));

        verify(videoProcessingTaskService, timeout(5000)).processAsync(eq(mediaAssetId));
    }
}
