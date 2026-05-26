package com.portfolio.clipcurator.ai;

import java.io.File;
import java.util.List;

public interface AiService {

    List<TranscriptSegment> transcribe(File audioFile);

    List<Float> getEmbedding(String textInput);

    List<Float> getEmbedding(File imageFile);
}
