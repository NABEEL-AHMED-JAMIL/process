package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.filechat.UncontendedFileIndexLock;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.FileChatMessageRequestDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ResponseDto;
import process.model.service.AiAgentService;
import process.model.service.EmbeddingService;
import process.media.MediaPort;
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
import java.util.Map;

/**
 * A file already indexed in OpenSearch for this exact bucket/key/etag must never have its raw
 * text re-extracted at all -- not by sendMessage, not by prepareContext. Before this, both
 * called {@code MediaPort.extractText} unconditionally, before ever checking
 * whether OpenSearch already had everything a question needed. For audio specifically that meant
 * a full re-transcription (tens of seconds, chunk by chunk) on every single visit to an
 * already-indexed file, even though nothing in the "already indexed" path ever reads the raw
 * text once retrieval has an answer.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class FileChatLazyExtractionTest {

    private static final String BUCKET = "docs";
    private static final String KEY = "episode.mp3";
    private static final String ETAG = "etag-123";
    private static final Long AGENT_ID = 2000L;

    @Mock private StorageBrowserService storageBrowserService;
    @Mock private MediaPort fileChatExtractionService;
    @Mock private AiAgentService aiAgentService;
    @Mock private OpenSearchRagClient openSearchRagClient;
    @Mock private EmbeddingService embeddingService;
    // Only emailExport reaches it; these cases never do. Present so the constructor resolves.

    private FileChatServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new FileChatServiceImpl(this.storageBrowserService,
            this.fileChatExtractionService, this.aiAgentService,
            this.openSearchRagClient, this.embeddingService, new UncontendedFileIndexLock());

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
        // null is what the extractor answers for a type it has no reader for; "" is a file it
        // read and found empty, which gets its own message now.
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG)).thenReturn(null);
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
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.openSearchRagClient.indexStateOf(BUCKET, KEY, ETAG))
            .thenReturn(OpenSearchRagClient.IndexState.INDEXED);

        ResponseDto response = this.service.prepareContext(BUCKET, KEY, AGENT_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.fileChatExtractionService, never())
            .extractText(anyString(), anyString(), anyString());
        @SuppressWarnings("unchecked")
        Map<String, Object> readiness = (Map<String, Object>) response.getData();
        assertThat(readiness.get("usingRetrieval")).isEqualTo(true);
        assertThat(readiness.get("truncated")).isEqualTo(false);
    }

    /**
     * This case used to assert the opposite -- that the already-indexed fast path deliberately did
     * NOT consult the embedding model, since there is no question to embed at prepare time. That
     * reasoning was about what this method can cheaply compute, and readiness is not a computation,
     * it is a prediction of what the very next sendMessage will do. resolveContext gates its whole
     * retrieval path on ragAvailable(), which is OpenSearch reachable AND the embedding model
     * genuinely up, so with Ollama down the answer comes from raw text cut to the provider's budget
     * no matter how many chunks OpenSearch still holds. Reporting usingRetrieval anyway put the
     * blue "answers are drawn from indexed excerpts of this file" banner above a truncated answer
     * and suppressed the truncation warning that should have been there instead.
     */
    @Test
    void prepareContextDoesNotPromiseRetrievalWhenTheEmbeddingModelIsDown() throws Exception {
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(false);
        lenient().when(this.openSearchRagClient.indexStateOf(BUCKET, KEY, ETAG))
            .thenReturn(OpenSearchRagClient.IndexState.INDEXED);
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn(longTranscript());

        ResponseDto response = this.service.prepareContext(BUCKET, KEY, AGENT_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> readiness = (Map<String, Object>) response.getData();
        assertThat(readiness.get("usingRetrieval"))
            .as("retrieval cannot run without the embedding model, so the panel must not claim it will")
            .isEqualTo(false);
        assertThat(readiness.get("truncated"))
            .as("and the warning the retrieval claim was suppressing has to appear instead")
            .isEqualTo(true);
        // Reading the raw file is the point, not a regression: once retrieval is off the table the
        // panel needs the real character counts, which only the extraction can supply.
        verify(this.fileChatExtractionService, times(1)).extractText(BUCKET, KEY, ETAG);
    }

    @Test
    void prepareContextExtractsNormallyWhenNotYetIndexed() throws Exception {
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.openSearchRagClient.indexStateOf(BUCKET, KEY, ETAG))
            .thenReturn(OpenSearchRagClient.IndexState.NOT_INDEXED);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(false);
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn("A short transcript.");

        ResponseDto response = this.service.prepareContext(BUCKET, KEY, AGENT_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.fileChatExtractionService, times(1)).extractText(BUCKET, KEY, ETAG);
    }

    /** Comfortably past Ollama's 24,000-character budget, so "truncated" is a real answer. */
    private static String longTranscript() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 30000) {
            sb.append("And then somebody said something else worth transcribing at length. ");
        }
        return sb.toString();
    }
}
