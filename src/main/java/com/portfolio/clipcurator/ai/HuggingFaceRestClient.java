package com.portfolio.clipcurator.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class HuggingFaceRestClient implements AiService {

    private static final int MAX_RETRIES = 3;
    private static final long RATE_LIMIT_BACKOFF_MILLIS = 5_000;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiToken;
    private final String whisperUrl;
    private final String clipUrl;

    public HuggingFaceRestClient(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${app.huggingface.api-token}") String apiToken,
            @Value("${app.huggingface.whisper-url}") String whisperUrl,
            @Value("${app.huggingface.clip-url}") String clipUrl
    ) {
        this.objectMapper = objectMapper;
        this.apiToken = requireNonBlank(apiToken, "app.huggingface.api-token");
        this.whisperUrl = requireNonBlank(whisperUrl, "app.huggingface.whisper-url");
        this.clipUrl = requireNonBlank(clipUrl, "app.huggingface.clip-url");

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);

        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public List<TranscriptSegment> transcribe(File audioFile) {
        Path requiredAudioPath = requireExistingFile(audioFile, "audioFile").toPath();
        byte[] audioBytes;
        try {
            audioBytes = Files.readAllBytes(requiredAudioPath);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read extracted audio file.", ex);
        }

        String base64Audio = Base64.getEncoder().encodeToString(audioBytes);

        Map<String, Object> requestBody = Map.of(
                "inputs", base64Audio,
                "parameters", Map.of("return_timestamps", true)
        );

        JsonNode response = postJsonWithRetry(whisperUrl, requestBody, "Whisper transcription");
        return parseTranscriptSegments(response);
    }

    @Override
    public List<Float> getEmbedding(String textInput) {
        String requiredTextInput = requireNonBlank(textInput, "textInput");
        JsonNode response = postJsonWithRetry(clipUrl, Map.of("inputs", requiredTextInput), "CLIP text embedding");
        return parseEmbedding(response);
    }

    @Override
    public List<Float> getEmbedding(File imageFile) {
        Path requiredImagePath = requireExistingFile(imageFile, "imageFile").toPath();
        byte[] imageBytes;
        try {
            imageBytes = Files.readAllBytes(requiredImagePath);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read frame file for CLIP embedding.", ex);
        }

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        Map<String, Object> requestBody = Map.of("inputs", "data:image/jpeg;base64," + base64Image);
        JsonNode response = postJsonWithRetry(clipUrl, requestBody, "CLIP visual embedding");
        return parseEmbedding(response);
    }

    private JsonNode postJsonWithRetry(String url, Object body, String operationName) {
        int attempt = 0;

        while (attempt < MAX_RETRIES) {
            attempt++;
            try {
                String responseBody = restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .headers(headers -> headers.setBearerAuth(apiToken))
                        .body(body)
                        .retrieve()
                        .body(String.class);

                if (responseBody == null || responseBody.isBlank()) {
                    throw new IllegalStateException(operationName + " returned an empty response body.");
                }

                JsonNode response = objectMapper.readTree(responseBody);
                String apiError = extractApiError(response);

                if (apiError == null) {
                    return response;
                }

                boolean loadingHint = isModelLoadingResponse(response, apiError);
                if ((loadingHint || isTransientApiError(apiError)) && attempt < MAX_RETRIES) {
                    sleepWithBackoff(attempt, loadingHint);
                    continue;
                }

                throw new IllegalStateException(operationName + " failed: " + apiError);
            } catch (HttpClientErrorException.TooManyRequests ex) {
                if (attempt >= MAX_RETRIES) {
                    throw new IllegalStateException(operationName + " failed after retries due to rate limiting.", ex);
                }
                sleepFixed(RATE_LIMIT_BACKOFF_MILLIS);
            } catch (HttpServerErrorException | ResourceAccessException ex) {
                if (attempt >= MAX_RETRIES) {
                    throw new IllegalStateException(operationName + " failed after retries.", ex);
                }
                sleepWithBackoff(attempt, false);
            } catch (RestClientException ex) {
                throw new IllegalStateException(operationName + " failed.", ex);
            } catch (IOException ex) {
                throw new IllegalStateException(operationName + " returned malformed JSON.", ex);
            }
        }

        throw new IllegalStateException(operationName + " failed after maximum retry attempts.");
    }

    private List<TranscriptSegment> parseTranscriptSegments(JsonNode response) {
        List<TranscriptSegment> segments = new ArrayList<>();

        JsonNode explicitSegments = response.path("segments");
        if (explicitSegments.isArray()) {
            for (JsonNode segment : explicitSegments) {
                String content = segment.path("text").asText("").trim();
                if (content.isBlank()) {
                    continue;
                }

                BigDecimal start = toScaledDecimal(segment.path("start"));
                BigDecimal end = toScaledDecimal(segment.path("end"));
                segments.add(new TranscriptSegment(start, end, content));
            }
        }

        JsonNode chunkSegments = response.path("chunks");
        if (segments.isEmpty() && chunkSegments.isArray()) {
            for (JsonNode chunk : chunkSegments) {
                String content = chunk.path("text").asText("").trim();
                if (content.isBlank()) {
                    continue;
                }

                JsonNode timestamp = chunk.path("timestamp");
                if (!timestamp.isArray() || timestamp.size() < 2) {
                    continue;
                }

                BigDecimal start = toScaledDecimal(timestamp.get(0));
                BigDecimal end = toScaledDecimal(timestamp.get(1));
                segments.add(new TranscriptSegment(start, end, content));
            }
        }

        if (segments.isEmpty()) {
            throw new IllegalStateException("Whisper response did not include timestamped transcript segments.");
        }

        return segments;
    }

    private List<Float> parseEmbedding(JsonNode response) {
        JsonNode vectorNode = locateVectorNode(response);
        if (!vectorNode.isArray() || vectorNode.isEmpty()) {
            throw new IllegalStateException("CLIP response did not include an embedding array.");
        }

        List<Float> embedding = new ArrayList<>(vectorNode.size());
        for (JsonNode valueNode : vectorNode) {
            if (!valueNode.isNumber()) {
                throw new IllegalStateException("CLIP embedding response contains non-numeric values.");
            }
            embedding.add((float) valueNode.asDouble());
        }

        if (embedding.size() != 512) {
            throw new IllegalStateException("Expected 512-dimensional embedding but received " + embedding.size());
        }

        return embedding;
    }

    private JsonNode locateVectorNode(JsonNode rootNode) {
        JsonNode node = rootNode;

        if (node.isObject()) {
            if (node.has("embedding")) {
                node = node.get("embedding");
            } else if (node.has("vector")) {
                node = node.get("vector");
            } else if (node.has("data") && node.get("data").isArray() && !node.get("data").isEmpty()) {
                JsonNode first = node.get("data").get(0);
                if (first.has("embedding")) {
                    node = first.get("embedding");
                }
            }
        }

        while (node.isArray() && !node.isEmpty() && node.get(0).isArray()) {
            node = node.get(0);
        }

        return node;
    }

    private String extractApiError(JsonNode response) {
        JsonNode errorNode = response.path("error");
        if (errorNode.isMissingNode() || errorNode.isNull()) {
            return null;
        }
        String error = errorNode.asText("").trim();
        return error.isBlank() ? null : error;
    }

    private boolean isModelLoadingResponse(JsonNode response, String errorMessage) {
        return response.has("estimated_time") || errorMessage.toLowerCase().contains("loading");
    }

    private boolean isTransientApiError(String errorMessage) {
        String lower = errorMessage.toLowerCase();
        return lower.contains("temporarily")
                || lower.contains("timeout")
                || lower.contains("please try again")
                || lower.contains("service unavailable");
    }

    private BigDecimal toScaledDecimal(JsonNode valueNode) {
        if (valueNode == null || valueNode.isNull()) {
            throw new IllegalStateException("Timestamp value is missing in AI response.");
        }

        try {
            return new BigDecimal(valueNode.asText()).setScale(3, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Timestamp value is not numeric: " + valueNode.asText(), ex);
        }
    }

    private void sleepWithBackoff(int attempt, boolean loadingHint) {
        if (loadingHint) {
            sleepFixed(RATE_LIMIT_BACKOFF_MILLIS);
            return;
        }

        long delayMillis = switch (attempt) {
            case 1 -> 1_000;
            case 2 -> 2_000;
            default -> 4_000;
        };
        sleepFixed(delayMillis);
    }

    private void sleepFixed(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry backoff interrupted.", ex);
        }
    }

    private String requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required configuration is missing: " + propertyName);
        }
        return value;
    }

    private File requireExistingFile(File file, String fieldName) {
        if (file == null) {
            throw new IllegalArgumentException(fieldName + " must not be null.");
        }
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException(fieldName + " does not exist: " + file);
        }
        return file;
    }
}
