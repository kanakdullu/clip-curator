package com.portfolio.clipcurator;

import com.portfolio.clipcurator.ai.AiService;
import com.portfolio.clipcurator.vector.PineconeVectorService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class ClipCuratorApplicationTests {

    @MockitoBean
    private AiService aiService;

    @MockitoBean
    private PineconeVectorService pineconeVectorService;

    @Test
    void contextLoads() {
    }
}
