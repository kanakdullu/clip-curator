package com.portfolio.clipcurator.search;

import com.portfolio.clipcurator.ai.AiService;
import com.portfolio.clipcurator.media.Transcript;
import com.portfolio.clipcurator.media.TranscriptRepository;
import com.portfolio.clipcurator.media.VisualFrame;
import com.portfolio.clipcurator.media.VisualFrameRepository;
import com.portfolio.clipcurator.storage.StorageService;
import com.portfolio.clipcurator.vector.PineconeVectorService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final int TOP_K = 15;
    private static final float MINIMUM_SCORE = 0.25f;

    private final AiService aiService;
    private final PineconeVectorService pineconeVectorService;
    private final TranscriptRepository transcriptRepository;
    private final VisualFrameRepository visualFrameRepository;
    private final StorageService storageService;

    public SearchService(
            AiService aiService,
            PineconeVectorService pineconeVectorService,
            TranscriptRepository transcriptRepository,
            VisualFrameRepository visualFrameRepository,
            StorageService storageService
    ) {
        this.aiService = aiService;
        this.pineconeVectorService = pineconeVectorService;
        this.transcriptRepository = transcriptRepository;
        this.visualFrameRepository = visualFrameRepository;
        this.storageService = storageService;
    }

    public List<SearchResultDto> search(String query) {
        String normalizedQuery = normalizeQuery(query);
        List<Float> queryEmbedding = aiService.getEmbedding(normalizedQuery);

        List<PineconeVectorService.VectorMatch> vectorMatches = pineconeVectorService.queryTopMatches(
                queryEmbedding,
                TOP_K,
                MINIMUM_SCORE
        );

        if (vectorMatches.isEmpty()) {
            return List.of();
        }

        List<UUID> matchedIds = vectorMatches.stream()
                .map(match -> parseUuidSafely(match.id()))
                .filter(Objects::nonNull)
                .toList();

        if (matchedIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, Transcript> transcriptsById = transcriptRepository.findAllByIdIn(matchedIds).stream()
                .collect(Collectors.toMap(Transcript::getId, transcript -> transcript, (left, right) -> left, LinkedHashMap::new));

        Map<UUID, VisualFrame> visualFramesById = visualFrameRepository.findAllByIdIn(matchedIds).stream()
                .collect(Collectors.toMap(VisualFrame::getId, visualFrame -> visualFrame, (left, right) -> left, LinkedHashMap::new));

        List<SearchResultDto> hydratedResults = new ArrayList<>();

        for (PineconeVectorService.VectorMatch vectorMatch : vectorMatches) {
            UUID matchId = parseUuidSafely(vectorMatch.id());
            if (matchId == null) {
                continue;
            }

            Transcript transcript = transcriptsById.get(matchId);
            if (transcript != null) {
                String s3VideoUrl = storageService.generatePresignedGetUrl(transcript.getMediaAsset().getS3Url());
                hydratedResults.add(new SearchResultDto(
                        transcript.getMediaAsset().getId(),
                        "audio",
                        vectorMatch.similarityScore(),
                        transcript.getStartTime(),
                        transcript.getContent(),
                        s3VideoUrl,
                        s3VideoUrl
                ));
                continue;
            }

            VisualFrame visualFrame = visualFramesById.get(matchId);
            if (visualFrame != null) {
                String s3VideoUrl = storageService.generatePresignedGetUrl(visualFrame.getMediaAsset().getS3Url());
                hydratedResults.add(new SearchResultDto(
                        visualFrame.getMediaAsset().getId(),
                        "visual",
                        vectorMatch.similarityScore(),
                        visualFrame.getTimestamp(),
                        null,
                        storageService.generatePresignedGetUrl(visualFrame.getS3ImageUrl()),
                        s3VideoUrl
                ));
            }
        }

        hydratedResults.sort(Comparator.comparing(SearchResultDto::similarityScore).reversed());
        return hydratedResults;
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query parameter 'q' is required.");
        }
        return query.trim();
    }

    private UUID parseUuidSafely(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
