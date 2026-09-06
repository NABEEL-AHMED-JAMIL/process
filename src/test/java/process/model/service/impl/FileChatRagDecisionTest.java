package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.AdHocPromptRequestDto;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The actual behaviour change: whether a question about a file is answered from retrieved
 * OpenSearch chunks, the whole (possibly truncated) raw text, or a mix -- RAG now runs for any
 * file size whenever it is available, not only ones too large to send whole, and an
 * already-indexed file skips re-indexing regardless of size. AiAgentService is mocked throughout,
 * so nothing here ever calls a real model -- these assertions are about which PATH
 * FileChatServiceImpl takes, not what an LLM says back.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class FileChatRagDecisionTest {

    private static final String BUCKET = "docs";
    private static final String KEY = "big-file.txt";
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
        lenient().when(this.storageBrowserService.getObjectMetadataCached(BUCKET, KEY))
            .thenReturn(new ObjectMetadataDto(KEY, KEY, 1000L, "now", ETAG, "text/plain", false));

        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider("Ollama"); // the smallest budget (24,000 chars), so small test strings
        config.setModel("qwen3:8b");  // cross the RAG threshold without needing huge fixtures
        config.setInstructions(null);
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

    // ---- RAG now runs for every file it can reach, including a small one ---------------------

    @Test
    void aSmallFileWithRagAvailableIsIndexedAndRetrievedToo() throws Exception {
        String text = "A short file, well under any provider's budget.";
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG)).thenReturn(text);
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embedAll(any())).thenReturn(Collections.singletonList(new float[] {1f}));
        lenient().when(this.embeddingService.embed("What does this say?")).thenReturn(new float[] {1f});
        // "Is this indexed" is now answered by the retrieval query itself (see
        // OpenSearchRagClient.RetrievalResult javadoc): empty on the outer check and the
        // double-checked-lock re-check, then the real chunk once indexing has run.
        lenient().when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenReturn(
                new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true),
                new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true),
                new OpenSearchRagClient.RetrievalResult(Collections.singletonList(text), true));

        ResponseDto response = this.service.sendMessage(this.request("What does this say?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        // The point of this feature: indexing runs so that a repeat question -- this one or a
        // later one -- can be answered from OpenSearch instead of re-reading the raw file, and
        // that only works if a small file gets indexed too, not only a large one.
        verify(this.openSearchRagClient, times(1))
            .indexChunks(any(), eq(BUCKET), eq(KEY), eq(ETAG), any(), any(), any());
    }

    // ---- a complete retrieval (every chunk came back) reads as the whole file, not excerpts ---

    @Test
    void aCompleteRetrievalIsFramedAsFileContentNotExcerpts() throws Exception {
        String text = "A short file, well under any provider's budget.";
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG)).thenReturn(text);
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embed(anyString())).thenReturn(new float[] {1f});
        lenient().when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenReturn(new OpenSearchRagClient.RetrievalResult(Collections.singletonList(text), true));

        ResponseDto response = this.service.sendMessage(this.request("What does this say?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("nothing was left out of a complete retrieval -- calling it 'excerpts' with a "
                + "'there may be more' caveat would be false, not just imprecise")
            .contains("--- FILE CONTENT ---")
            .doesNotContain("--- RELEVANT EXCERPTS FROM THE FILE ---")
            .doesNotContain("there may be other content");
    }

    // ---- a file with RAG unavailable never touches OpenSearch or the embedding model, any size

    @Test
    void aSmallFileWithNoRagAvailableNeverTouchesRagEither() throws Exception {
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn("A short file, well under any provider's budget.");
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(false);

        ResponseDto response = this.service.sendMessage(this.request("What does this say?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.openSearchRagClient, never())
            .searchRelevantChunks(anyString(), anyString(), anyString(), any(), anyInt());
        verify(this.openSearchRagClient, never()).indexChunks(any(), anyString(), anyString(), anyString(), any(), any(), any());
        verify(this.embeddingService, never()).embed(anyString());
    }

    // ---- a large file with no RAG available falls back to the old truncation behaviour -------

    @Test
    void aLargeFileWithNoRagAvailableFallsBackToTruncation() throws Exception {
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn(bigText());
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(false);

        ResponseDto response = this.service.sendMessage(this.request("Summarise this."));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.openSearchRagClient, never())
            .searchRelevantChunks(anyString(), anyString(), anyString(), any(), anyInt());

        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("must still carry the truncation note, exactly as before RAG existed")
            .contains("--- FILE CONTENT ---")
            .contains("[content truncated");
    }

    // ---- a large file with RAG available, not yet indexed: indexes, then retrieves -----------

    @Test
    void aLargeUnindexedFileWithRagAvailableIndexesThenRetrieves() throws Exception {
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn(bigText());
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.model()).thenReturn("nomic-embed-text");
        lenient().when(this.embeddingService.embedAll(any())).thenReturn(
            Arrays.asList(new float[] {1f}, new float[] {2f}, new float[] {3f}));
        lenient().when(this.embeddingService.embed("What does the budget section say?"))
            .thenReturn(new float[] {9f});
        // Empty on the outer check and the double-checked-lock re-check ("not indexed yet"), the
        // real chunks once indexing has actually run.
        lenient().when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenReturn(
                new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true),
                new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true),
                new OpenSearchRagClient.RetrievalResult(
                    Arrays.asList("The budget for Q1 is $50,000.", "The budget for Q2 is $75,000."), false));

        ResponseDto response = this.service.sendMessage(this.request("What does the budget section say?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.embeddingService, times(1)).embedAll(any());
        // Recorded on every indexed chunk so a later change of embedding.model doesn't silently
        // mix vectors from two different models in the same similarity search.
        verify(this.openSearchRagClient, times(1))
            .indexChunks(any(), eq(BUCKET), eq(KEY), eq(ETAG), any(), any(), eq("nomic-embed-text"));

        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("the retrieved chunks, not the truncated whole file, must reach the model")
            .contains("--- RELEVANT EXCERPTS FROM THE FILE ---")
            .contains("The budget for Q1 is $50,000.")
            .contains("The budget for Q2 is $75,000.")
            .doesNotContain("[content truncated");
    }

    // ---- the whole point of etag-keyed skip: a second question against the same file version -

    @Test
    void anAlreadyIndexedFileSkipsReindexingEntirely() throws Exception {
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn(bigText());
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embed(anyString())).thenReturn(new float[] {1f});
        lenient().when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenReturn(new OpenSearchRagClient.RetrievalResult(
                Collections.singletonList("Some retrieved chunk."), true));

        this.service.sendMessage(this.request("Another question about the same file."));

        verify(this.openSearchRagClient, never())
            .indexChunks(any(), anyString(), anyString(), anyString(), any(), any(), any());
        verify(this.embeddingService, never()).embedAll(any());
        // Only the question itself is embedded (for retrieval), never the file's own content --
        // that is what "reuse the existing OpenSearch data instead of fetching, extracting,
        // chunking and embedding the file again" actually means in code.
        verify(this.embeddingService, times(1)).embed(anyString());
    }

    // ---- retrieval coming back empty must not produce an empty or broken prompt --------------

    @Test
    void emptyRetrievalFallsBackToTruncationRatherThanAnEmptyPrompt() throws Exception {
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn(bigText());
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embedAll(any())).thenReturn(
            Arrays.asList(new float[] {1f}, new float[] {2f}, new float[] {3f}));
        lenient().when(this.embeddingService.embed(anyString())).thenReturn(new float[] {1f});
        // Empty every time: the outer check and the lock re-check read as "not indexed yet" (so
        // indexing runs), and even the fetch straight after indexing still comes back empty --
        // e.g. the write did not land before the read. Genuinely indexed content never does this
        // (see the RetrievalResult javadoc), so this is the actual write-failed/refresh-lag case
        // the fallback exists for, not the old "indexed but nothing relevant" scenario, which
        // this collapsed design can no longer distinguish from "not indexed" -- by design, since
        // an indexed file with any chunks always ranks and returns at least one of them.
        lenient().when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenReturn(new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true));

        ResponseDto response = this.service.sendMessage(this.request("A question."));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("empty retrieval is a safety net back to the old truncated-whole-file behaviour, "
                + "not an empty '--- FILE CONTENT ---' block")
            .contains("--- FILE CONTENT ---")
            .contains("[content truncated");
    }

    // ---- a RAG pipeline exception degrades the answer, never the whole request ---------------

    @Test
    void aRagFailureDegradesToTruncationRatherThanFailingTheRequest() throws Exception {
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn(bigText());
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embed(anyString()))
            .thenThrow(new RuntimeException("OpenSearch timed out"));

        ResponseDto response = this.service.sendMessage(this.request("A question."));

        assertThat(response.getStatus())
            .as("a broken RAG dependency must degrade the answer quality, not break the feature")
            .isEqualTo(ProcessUtil.SUCCESS);
    }

    /** Comfortably over Ollama's 24,000-char limit, so resolveContext's RAG branch is reached. */
    private static String bigText() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 30000) {
            sb.append("This is filler content that pads the file past the provider's inline budget. ");
        }
        return sb.toString();
    }
}
