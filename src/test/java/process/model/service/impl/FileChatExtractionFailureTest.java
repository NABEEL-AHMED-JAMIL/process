package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.FileChatMessageRequestDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ResponseDto;
import process.model.service.AiAgentService;
import process.model.service.EmbeddingService;
import process.model.service.FileChatExtractionService;
import process.model.service.StorageBrowserService;
import process.util.OpenSearchRagClient;
import process.util.ProcessUtil;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * A real bug: extraction itself failing (a vision-model call erroring out, a transcription
 * failure, a storage read fault -- anything extractText genuinely throws rather than returning
 * null/empty for) is not a "RAG infrastructure hiccup" resolveContext's generic catch can degrade
 * past, because the raw-content fallback IS the same extraction call. Before this fix, that
 * exception got cached by memoizedExtraction and rethrown as-is, uncaught, out of sendMessage --
 * an unhandled exception instead of the graceful ResponseDto(ERROR, ...) every other extraction
 * failure produces.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class FileChatExtractionFailureTest {

    private static final String BUCKET = "docs";
    private static final String KEY = "photo.jpg";
    private static final String ETAG = "etag-123";
    private static final Long AGENT_ID = 3000L;

    @Mock private StorageBrowserService storageBrowserService;
    @Mock private FileChatExtractionService fileChatExtractionService;
    @Mock private AiAgentService aiAgentService;
    @Mock private OpenSearchRagClient openSearchRagClient;
    @Mock private EmbeddingService embeddingService;

    private FileChatServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new FileChatServiceImpl(this.storageBrowserService,
            this.fileChatExtractionService, this.aiAgentService,
            this.openSearchRagClient, this.embeddingService);

        lenient().when(this.storageBrowserService.listBuckets())
            .thenReturn(Collections.singletonList(new BucketSummaryDto("Docs", BUCKET, "MINIO")));
        lenient().when(this.storageBrowserService.getObjectMetadataCached(BUCKET, KEY))
            .thenReturn(new ObjectMetadataDto(KEY, KEY, 1000L, "now", ETAG, "image/jpeg", false));

        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider("Ollama");
        config.setModel("llava:7b");
        lenient().when(this.aiAgentService.resolveRuntimeConfig(AGENT_ID))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Resolved.", config));
    }

    private FileChatMessageRequestDto request(String message) {
        FileChatMessageRequestDto dto = new FileChatMessageRequestDto();
        dto.setBucket(BUCKET);
        dto.setKey(KEY);
        dto.setAiAgentId(AGENT_ID);
        dto.setMessage(message);
        return dto;
    }

    @Test
    void anExtractionFailureDuringRagIndexingDegradesToAnErrorResponseRatherThanThrowing() throws Exception {
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embed(anyString())).thenReturn(new float[] {1f});
        // Not yet indexed -- both the outer check and the double-checked-lock re-check come back
        // empty, so resolveContext reaches into fileTextSupplier.get() to chunk/embed/index.
        lenient().when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenReturn(new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true));
        // The vision model call inside extractText genuinely throws -- not a null/empty return.
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenThrow(new IllegalStateException("HTTP 500: vision model unreachable"));

        ResponseDto response = this.service.sendMessage(this.request("What's in this image?"));

        assertThat(response.getStatus())
            .as("an extraction failure must degrade to a plain error response, never escape as an "
                + "unhandled exception -- sendMessage itself must not throw here")
            .isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("jpg");
    }

    @Test
    void anExtractionFailureWithNoRagConfiguredStillDegradesGracefully() throws Exception {
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(false);
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenThrow(new IllegalStateException("HTTP 500: vision model unreachable"));

        ResponseDto response = this.service.sendMessage(this.request("What's in this image?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
    }
}
