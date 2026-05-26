package com.portfolio.clipcurator.vector;

import com.google.protobuf.Struct;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.VectorWithUnsignedIndices;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static io.pinecone.commons.IndexInterface.buildUpsertVectorWithUnsignedIndices;

@Service
public class PineconeVectorService {

    private static final int EXPECTED_DIMENSION = 512;

    private final Pinecone pinecone;
    private final Index index;
    private final String indexName;
    private final String namespace;

    public PineconeVectorService(
            @Value("${app.pinecone.api-key}") String apiKey,
            @Value("${app.pinecone.index-name}") String indexName,
            @Value("${app.pinecone.namespace:default}") String namespace
    ) {
        this.indexName = requireNonBlank(indexName, "app.pinecone.index-name");
        this.namespace = requireNonBlank(namespace, "app.pinecone.namespace");

        String requiredApiKey = requireNonBlank(apiKey, "app.pinecone.api-key");
        this.pinecone = new Pinecone.Builder(requiredApiKey).build();
        this.index = pinecone.getIndexConnection(this.indexName);
    }

    @PostConstruct
    void validateConnection() {
        try {
            pinecone.describeIndex(indexName);
            index.describeIndexStats();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to connect to Pinecone index '" + indexName + "'.", ex);
        }
    }

    public void upsert(String id, List<Float> embedding, String type, UUID mediaAssetId) {
        String requiredId = requireNonBlank(id, "vector id");
        String requiredType = requireNonBlank(type, "metadata type");

        if (embedding == null || embedding.size() != EXPECTED_DIMENSION) {
            throw new IllegalArgumentException("Embedding must contain exactly 512 dimensions.");
        }

        Struct metadata = Struct.newBuilder()
            .putFields("type", com.google.protobuf.Value.newBuilder().setStringValue(requiredType).build())
            .putFields("media_asset_id", com.google.protobuf.Value.newBuilder().setStringValue(mediaAssetId.toString()).build())
                .build();

        VectorWithUnsignedIndices vector = buildUpsertVectorWithUnsignedIndices(
                requiredId,
                embedding,
                null,
                null,
                metadata
        );

        index.upsert(List.of(vector), namespace);
    }

    public List<VectorMatch> queryTopMatches(List<Float> embedding, int topK, float minimumScore) {
        if (topK < 1) {
            throw new IllegalArgumentException("topK must be greater than 0.");
        }

        if (embedding == null || embedding.size() != EXPECTED_DIMENSION) {
            throw new IllegalArgumentException("Embedding must contain exactly 512 dimensions.");
        }

        QueryResponseWithUnsignedIndices queryResponse = index.queryByVector(topK, embedding, namespace, true, false);

        List<VectorMatch> matches = new ArrayList<>();
        for (ScoredVectorWithUnsignedIndices match : queryResponse.getMatchesList()) {
            if (match == null || match.getScore() < minimumScore) {
                continue;
            }

            String id = match.getId();
            if (id == null || id.isBlank()) {
                continue;
            }

            String type = extractMetadataValue(match.getMetadata(), "type");
            String mediaAssetId = extractMetadataValue(match.getMetadata(), "media_asset_id");

            matches.add(new VectorMatch(id, match.getScore(), type, mediaAssetId));
        }

        matches.sort(Comparator.comparing(VectorMatch::similarityScore).reversed());
        return matches;
    }

    private String extractMetadataValue(Struct metadata, String fieldName) {
        if (metadata == null) {
            return null;
        }

        com.google.protobuf.Value value = metadata.getFieldsMap().get(fieldName);
        if (value == null) {
            return null;
        }

        String extracted = value.getStringValue();
        return extracted == null || extracted.isBlank() ? null : extracted;
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required configuration is missing: " + fieldName);
        }
        return value;
    }

    public record VectorMatch(
            String id,
            float similarityScore,
            String type,
            String mediaAssetId
    ) {
    }
}
