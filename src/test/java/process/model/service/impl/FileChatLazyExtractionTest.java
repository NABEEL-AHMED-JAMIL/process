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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A file already indexed in OpenSearch for this exact bucket/key/etag must never have its raw
 * text re-extracted at all -- not by sendMessage, not by prepareContext. Before this, both
 * called {@code FileChatExtractionService.extractText} unconditionally, before ever checking
 * whether OpenSearch already had everything a question needed. For audio specifically that meant
 * a full re-transcription (tens of seconds, chunk by chunk) on every single visit to an
 * already-indexed file, even though nothing in the "already indexed" path ever reads the raw
 * text once retrieval has an answer.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class FileChatLazyExtractionTest {

    private static final String BUCKET = "docs";
    private static final String KEY = "episode.mp3";
    private static final String ETAG = "etag-123";
    private static final Long AGENT_ID = 2000L;

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
        lenient().when(this.storageBrowserService.getObjectMetadata(BUCKET, KEY))
            .thenReturn(new ObjectMetadataDto(KEY, KEY, 1000L, "now", ETAG, "audio/mpeg", false));
        lenient().when(this.storageBrowserService.getObjectMetadataCached(BUCKET, KEY))
            .thenReturn(new ObjectMetadataDto(KEY, KEY, 1000L, "now", ETAG, "audio/mpeg", false));

        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider("Ollama");
        config.setModel("qwen3:8b");
        lenient().when(this.aiAgentService.resolveRuntimeConfig(AGENT_ID))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Resolved.", config));
        lenient().when(this.aiAgentService.processAdHoc(any()))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Replied.", "the answer"));
    }

    private FileChatMessageRequestDto request(String message) {
        FileChatMessageRequestDto dto = new FileChatMessageRequestDto();
        dto.setBucket(BUCKET);
        dto.setKey(KEY);
        dto.setAiAgentId(AGENT_ID);
        dto.setMessage(message);
        return dto;
    }

    // ---- sendMessage ----------------------------------------------------------------------

    @Test
    void sendMessageNeverExtractsWhenAlreadyIndexed() throws Exception {
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embed(anyString())).thenReturn(new float[] {1f});
        lenient().when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenReturn(new OpenSearchRagClient.RetrievalResult(
                Collections.singletonList("Grilled chicken and a garden salad."), true));

        ResponseDto response = this.service.sendMessage(this.request("What's on the menu?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.fileChatExtractionService, never())
            .extractText(anyString(), anyString(), anyString());
    }

    @Test
    void sendMessageExtractsAtMostOnceWhenNotYetIndexed() throws Exception {
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn("A transcript long enough to need chunking. ".repeat(1000));
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embed(anyString())).thenReturn(new float[] {1f});
        lenient().when(this.embeddingService.embedAll(any()))
            .thenReturn(Collections.singletonList(new float[] {1f}));
        lenient().when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenReturn(
                new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true),
                new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true),
                new OpenSearchRagClient.RetrievalResult(Collections.singletonList("chunk"), true));

        ResponseDto response = this.service.sendMessage(this.request("Summarise this."));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.fileChatExtractionService, times(1))
            .extractText(BUCKET, KEY, ETAG);
    }

    @Test
    void sendMessageStillReportsAnUnsupportedFileTypeRatherThanARagWarning() throws Exception {
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG)).thenReturn("");
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embed(anyString())).thenReturn(new float[] {1f});
        lenient().when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenReturn(new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true));

        ResponseDto response = this.service.sendMessage(this.request("What does this say?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage())
            .as("must be the couldn't-read-this-file-type message, not a generic AI failure")
            .contains("readable content");
        verify(this.fileChatExtractionService, times(1)).extractText(BUCKET, KEY, ETAG);
    }

    // ---- prepareContext -------------------------------------------------------------------

    @Test
    void prepareContextNeverExtractsWhenAlreadyIndexed() throws Exception {
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.openSearchRagClient.isIndexed(BUCKET, KEY, ETAG)).thenReturn(true);

        ResponseDto response = this.service.prepareContext(BUCKET, KEY, AGENT_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.fileChatExtractionService, never())
            .extractText(anyString(), anyString(), anyString());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> readiness = (java.util.Map<String, Object>) response.getData();
        assertThat(readiness.get("usingRetrieval")).isEqualTo(true);
        assertThat(readiness.get("truncated")).isEqualTo(false);
    }

    @Test
    void prepareContextFastPathDoesNotNeedTheEmbeddingModelReachable() throws Exception {
        // isAvailable() deliberately left unstubbed (defaults to false) -- the fast path must
        // only need OpenSearch itself, since there is no question yet to embed at prepare time.
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.openSearchRagClient.isIndexed(BUCKET, KEY, ETAG)).thenReturn(true);

        ResponseDto response = this.service.prepareContext(BUCKET, KEY, AGENT_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.embeddingService, never()).isAvailable();
    }

    @Test
    void prepareContextExtractsNormallyWhenNotYetIndexed() throws Exception {
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.openSearchRagClient.isIndexed(BUCKET, KEY, ETAG)).thenReturn(false);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(false);
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn("A short transcript.");

        ResponseDto response = this.service.prepareContext(BUCKET, KEY, AGENT_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.fileChatExtractionService, times(1)).extractText(BUCKET, KEY, ETAG);
    }
}
