package com.portfolio.clipcurator.ai;

import java.math.BigDecimal;

public record TranscriptSegment(
        BigDecimal startTime,
        BigDecimal endTime,
        String content
) {
}
