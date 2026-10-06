package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import process.filechat.FileIndexLock;
import process.filechat.RedisFileIndexLock;
import process.filechat.RedisFileIndexLocks;
import process.media.MediaPort;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.FileChatMessageRequestDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ResponseDto;
import process.model.service.AiAgentService;
import process.model.service.EmbeddingService;
import process.model.service.StorageBrowserService;
import process.util.OpenSearchRagClient;
import process.util.ProcessUtil;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * MIG-111 at the level the board asks for: two instances of File Chat -- two process replicas,
 * each with its own FileChatServiceImpl and its own RedisFileIndexLock over one Redis -- asked
 * about the same unindexed file at the same moment extract it exactly once between them.
 *
 * What the two replicas share is what two real ones share: the OpenSearch index (one flag here,
 * flipped by whichever instance writes the chunks) and Media & Documents (one counting extraction
 * stub; for audio every call is a fresh transcription). Everything else is per instance.
 */
class FileChatIndexAcrossInstancesTest {

    private static final String BUCKET = "docs";
    private static final String KEY = "talk.mp3";
    private static final String ETAG = "etag-123";
    private static final Long AGENT_ID = 2000L;
    private static final Duration LEASE = Duration.ofMillis(600);

    private RedisFileIndexLocks locks;
    private final AtomicBoolean indexed = new AtomicBoolean(false);
    private final AtomicInteger extractions = new AtomicInteger();
    private final AtomicInteger indexWrites = new AtomicInteger();
    /** Every search answers this while set, whatever the index holds -- an outage. */
    private final AtomicBoolean clusterDown = new AtomicBoolean(false);
    private CountDownLatch firstSearches = new CountDownLatch(0);
    private MediaPort media;
    /** Runs inside every extraction, before it returns. */
    private volatile ThrowingRunnable duringExtraction = () -> { };

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @BeforeEach
    void setUp() throws Exception {
        this.locks = RedisFileIndexLocks.open();
        this.media = mock(MediaPort.class);
        lenient().when(this.media.extractText(BUCKET, KEY, ETAG)).thenAnswer(invocation -> {
            this.extractions.incrementAndGet();
            Thread.sleep(200); // a transcription takes a while; the other instance is waiting, not idle
            this.duringExtraction.run();
            return bigText();
        });
    }

    @AfterEach
    void tearDown() {
        if (this.locks != null) this.locks.close();
    }

    @Test
    void twoInstancesAskedAboutTheSameUnindexedFileExtractItExactlyOnce() throws Exception {
        this.firstSearches = new CountDownLatch(2);
        Replica a = this.replica(this.locks.instance(LEASE, Duration.ofSeconds(20)));
        Replica b = this.replica(this.locks.instance(LEASE, Duration.ofSeconds(20)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseDto> first = pool.submit(() -> a.service.sendMessage(request("Question one.")));
            Future<ResponseDto> second = pool.submit(() -> b.service.sendMessage(request("Question two.")));
            assertThat(first.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
            assertThat(second.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(this.extractions.get()).as("one transcription between two replicas").isEqualTo(1);
        assertThat(this.indexWrites.get()).as("one delete-then-write into the index").isEqualTo(1);
        assertThat(this.locks.keysLeft()).as("the lock is given back").isEmpty();
    }

    /** The fast path takes no lock at all: an indexed file is answered from its chunks. */
    @Test
    void anAlreadyIndexedFileNeverTakesTheLockOrExtracts() throws Exception {
        this.indexed.set(true);
        AtomicInteger acquired = new AtomicInteger();
        RedisFileIndexLock real = this.locks.instance(LEASE, Duration.ofSeconds(1));
        FileIndexLock counting = (bucket, key, etag) -> {
            acquired.incrementAndGet();
            return real.acquire(bucket, key, etag);
        };
        Replica a = this.replica(counting);

        assertThat(a.service.sendMessage(request("Again?")).getStatus()).isEqualTo(ProcessUtil.SUCCESS);

        assertThat(acquired.get()).isZero();
        assertThat(this.extractions.get()).isZero();
        assertThat(this.indexWrites.get()).isZero();
    }

    /**
     * A replica that dies inside the index lock, mid-transcription, leaves the lock held and the
     * file unindexed. The survivor waits for the lease, takes the lock, re-checks, and does the work
     * the dead one never finished -- once.
     */
    @Test
    void aReplicaThatDiesInsideTheLockHandsItOnByItsLease() throws Exception {
        RedisFileIndexLock dying = this.locks.instance(LEASE, Duration.ofSeconds(1));
        dying.acquire(BUCKET, KEY, ETAG);
        this.locks.crash(dying);
        Replica survivor = this.replica(this.locks.instance(LEASE, Duration.ofSeconds(10)));

        ResponseDto response = survivor.service.sendMessage(request("Anyone there?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(this.extractions.get()).isEqualTo(1);
        assertThat(this.indexWrites.get()).isEqualTo(1);
    }

    /**
     * aClusterThatDiesInsideTheIndexLockStillDoesNotGetReindexed, across the lease: a lock won from a
     * dead holder is no licence either. When OpenSearch has gone down by the time the survivor's
     * re-check runs, it indexes nothing and answers from the raw file.
     */
    @Test
    void aClusterThatDiesWhileTheSurvivorWaitsForADeadHoldersLeaseIsStillNotReindexed() throws Exception {
        RedisFileIndexLock dying = this.locks.instance(LEASE, Duration.ofSeconds(1));
        dying.acquire(BUCKET, KEY, ETAG);
        this.locks.crash(dying);
        RedisFileIndexLock survivorLock = this.locks.instance(LEASE, Duration.ofSeconds(10));
        FileIndexLock clusterDiesWhileWaiting = (bucket, key, etag) -> {
            FileIndexLock.Held held = survivorLock.acquire(bucket, key, etag);
            this.clusterDown.set(true);
            return held;
        };
        Replica survivor = this.replica(clusterDiesWhileWaiting);

        ResponseDto response = survivor.service.sendMessage(request("Anyone there?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(this.indexWrites.get()).isZero();
        verify(survivor.embeddings, never()).embedAll(any());
    }

    /**
     * The other side of the lease: a holder paused past it (a long GC, a partition from Redis) may
     * find, when its extraction returns, that the lock has passed to another replica. It must not
     * then write -- two writers would interleave their delete-then-write in the index.
     */
    @Test
    void aHolderThatLostItsLeaseWhileExtractingDoesNotWriteTheIndex() throws Exception {
        RedisFileIndexLock paused = this.locks.instance(LEASE, Duration.ofSeconds(1));
        RedisFileIndexLock other = this.locks.instance(LEASE, Duration.ofSeconds(5));
        FileIndexLock.Held[] taken = new FileIndexLock.Held[1];
        this.duringExtraction = () -> {
            this.locks.crash(paused); // no renewals: the pause outlives the lease
            taken[0] = other.acquire(BUCKET, KEY, ETAG);
        };
        Replica a = this.replica(paused);

        ResponseDto response = a.service.sendMessage(request("A question."));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(this.extractions.get()).isEqualTo(1);
        assertThat(this.indexWrites.get()).as("no write under a lock that is someone else's now").isZero();
        assertThat(taken[0].stillHeld()).as("and the late holder's close did not free the new one's").isTrue();
        taken[0].close();
    }

    /**
     * A waiter whose wait runs out while a live holder is still transcribing must not start a second
     * transcription of its own -- that is the very cost the lock exists to save. It says the file is
     * still being prepared, and the next question finds it indexed.
     */
    @Test
    void aWaiterThatWaitsOutALiveHolderDoesNotExtractItself() throws Exception {
        RedisFileIndexLock holderLock = this.locks.instance(LEASE, Duration.ofSeconds(1));
        FileIndexLock.Held holder = holderLock.acquire(BUCKET, KEY, ETAG);
        try {
            Replica waiter = this.replica(this.locks.instance(LEASE, Duration.ofMillis(400)));

            ResponseDto response = waiter.service.sendMessage(request("Ready yet?"));

            assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
            assertThat(response.getMessage()).contains("still being prepared");
            assertThat(this.extractions.get()).isZero();
            assertThat(this.indexWrites.get()).isZero();
        } finally {
            holder.close();
        }
    }

    /**
     * Without Redis there is no lock, and without the lock nothing is written to the index: the
     * question is answered from the raw file, as for an OpenSearch outage.
     */
    @Test
    void withNoRedisTheFileIsAnsweredRawAndNeverIndexedUnlocked() throws Exception {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory("localhost", 1);
        nowhere.afterPropertiesSet();
        try {
            Replica a = this.replica(new RedisFileIndexLock(RedisFileIndexLocks.template(nowhere), "x:",
                LEASE, Duration.ofMillis(200), Executors.newSingleThreadScheduledExecutor()));

            ResponseDto response = a.service.sendMessage(request("A question."));

            assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
            assertThat(this.indexWrites.get()).isZero();
            verify(a.embeddings, never()).embedAll(any());
        } finally {
            nowhere.destroy();
        }
    }

    // -- one replica ----------------------------------------------------------------------------

    private static final class Replica {
        FileChatServiceImpl service;
        EmbeddingService embeddings;
    }

    private Replica replica(FileIndexLock lock) throws Exception {
        StorageBrowserService storage = mock(StorageBrowserService.class);
        lenient().when(storage.listBuckets())
            .thenReturn(Collections.singletonList(new BucketSummaryDto("Docs", BUCKET, "MINIO")));
        lenient().when(storage.getObjectMetadataCached(BUCKET, KEY))
            .thenReturn(new ObjectMetadataDto(KEY, KEY, 1000L, "now", ETAG, "audio/mpeg", false));

        AiAgentService ai = mock(AiAgentService.class);
        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider("Ollama");
        config.setModel("qwen3:8b");
        lenient().when(ai.resolveRuntimeConfig(AGENT_ID)).thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Resolved.", config));
        lenient().when(ai.processAdHoc(any())).thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Replied.", "the answer"));

        EmbeddingService embeddings = mock(EmbeddingService.class);
        lenient().when(embeddings.isAvailable()).thenReturn(true);
        lenient().when(embeddings.embed(anyString())).thenReturn(new float[] {1f});
        lenient().when(embeddings.embedAll(any())).thenReturn(Collections.singletonList(new float[] {1f}));

        OpenSearchRagClient openSearch = mock(OpenSearchRagClient.class);
        lenient().when(openSearch.isEnabled()).thenReturn(true);
        lenient().when(openSearch.searchRelevantChunks(eq(BUCKET), eq(KEY), eq(ETAG), any(), eq(8)))
            .thenAnswer(invocation -> {
                if (this.clusterDown.get()) {
                    return OpenSearchRagClient.RetrievalResult.unavailable();
                }
                if (!this.indexed.get() && this.firstSearches.getCount() > 0) {
                    // Both replicas see "not indexed" before either can do anything about it.
                    this.firstSearches.countDown();
                    this.firstSearches.await(5, TimeUnit.SECONDS);
                }
                return this.indexed.get()
                    ? new OpenSearchRagClient.RetrievalResult(Collections.singletonList("a chunk"), true)
                    : new OpenSearchRagClient.RetrievalResult(Collections.emptyList(), true);
            });
        lenient().when(openSearch.indexChunks(any(), eq(BUCKET), eq(KEY), eq(ETAG), any(), any(), any()))
            .thenAnswer(invocation -> {
                this.indexWrites.incrementAndGet();
                this.indexed.set(true);
                return null;
            });

        Replica replica = new Replica();
        replica.service = new FileChatServiceImpl(storage, this.media, ai, openSearch, embeddings, lock);
        replica.embeddings = embeddings;
        return replica;
    }

    private static FileChatMessageRequestDto request(String message) {
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
