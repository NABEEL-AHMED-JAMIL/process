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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two concurrent requests for the SAME not-yet-indexed file (bucket+key+etag) must chunk, embed
 * and index it exactly once between them, not once each -- the scenario a double-tab or a
 * double-submit on a slow-to-index file produces. Real threads, not a sequential simulation: the
 * mocked {@code searchRelevantChunks} carries real shared, mutable state (an {@link AtomicBoolean}
 * flipped by the mocked {@code indexChunks}) and a deliberate delay on its first read, so both
 * threads are forced to observe "not indexed" before either can finish the work -- the exact
 * window {@code resolveContext}'s per-file lock exists to close.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class FileChatConcurrentIndexingTest {

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
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG)).thenReturn(bigText());

        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider("Ollama");
        config.setModel("qwen3:8b");
        lenient().when(this.aiAgentService.resolveRuntimeConfig(AGENT_ID))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Resolved.", config));
        lenient().when(this.aiAgentService.processAdHoc(any()))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Replied.", "the answer"));

        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(true);
        lenient().when(this.embeddingService.isAvailable()).thenReturn(true);
        lenient().when(this.embeddingService.embed(anyString())).thenReturn(new float[] {1f});
        lenient().when(this.embeddingService.embedAll(any()))
            .thenReturn(Collections.singletonList(new float[] {1f}));
    }

    @Test
    void twoConcurrentRequestsForTheSameUnindexedFileIndexItExactlyOnce() throws Exception {
        AtomicBoolean indexed = new AtomicBoolean(false);
        CountDownLatch bothArrived = new CountDownLatch(2);

        when(this.openSearchRagClient.searchRelevantChunks(
                eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenAnswer(invocation -> {
                if (!indexed.get()) {
                    // Widen the race window on purpose: without the fix, both threads reliably
                    // observe "not indexed" here before either has a chance to index.
                    bothArrived.countDown();
                    bothArrived.await(2, TimeUnit.SECONDS);
                }
                return indexed.get()
                    ? new OpenSearchRagClient.RetrievalResult(Collections.singletonList("a chunk"), true)
                    : new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true);
            });
        doAnswer(invocation -> {
            indexed.set(true);
            return null;
        }).when(this.openSearchRagClient).indexChunks(any(), eq(BUCKET), eq(KEY), eq(ETAG), any(), any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseDto> first = pool.submit(() -> this.service.sendMessage(this.request("Question one.")));
            Future<ResponseDto> second = pool.submit(() -> this.service.sendMessage(this.request("Question two.")));

            assertThat(first.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
            assertThat(second.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        } finally {
            pool.shutdownNow();
        }

        verify(this.embeddingService, times(1)).embedAll(any());
        verify(this.openSearchRagClient, times(1))
            .indexChunks(any(), eq(BUCKET), eq(KEY), eq(ETAG), any(), any(), any());
    }

    private FileChatMessageRequestDto request(String message) {
        FileChatMessageRequestDto dto = new FileChatMessageRequestDto();
        dto.setBucket(BUCKET);
        dto.setKey(KEY);
        dto.setAiAgentId(AGENT_ID);
        dto.setMessage(message);
        return dto;
    }

    private static String bigText() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 30000) {
            sb.append("This is filler content that pads the file past the provider's inline budget. ");
        }
        return sb.toString();
    }
}
