package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.pojo.LookupData;
import process.model.repository.LookupDataRepository;
import process.util.EncryptionUtil;
import process.util.InMemoryVersionStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-66 / MIG-110, T6: a lookup edited on instance A is served by instance B at B's next poll (2
 * seconds in production), not at B's next restart. The shared version is Redis in production;
 * platform-commons' SharedCacheVersionTest pins the outage bound (maxAge, 30 s here).
 */
class LookupDataCacheAcrossInstancesTest {

    private final LookupDataRepository repository = mock(LookupDataRepository.class);
    private final AtomicReference<List<LookupData>> table = new AtomicReference<>();
    private final InMemoryVersionStore redis = new InMemoryVersionStore();

    private static LookupData row(String type, String value) {
        LookupData lookup = new LookupData();
        lookup.setLookupId((long) type.hashCode());
        lookup.setLookupType(type);
        lookup.setLookupValue(value);
        lookup.setEncrypted(false);
        return lookup;
    }

    private LookupDataCacheService instance() {
        LookupDataCacheService cache = new LookupDataCacheService(this.repository, mock(EncryptionUtil.class), this.redis, null);
        cache.initialise(false);
        return cache;
    }

    @BeforeEach
    void table() {
        this.table.set(Collections.singletonList(row("BUCKET_LIST", "etl-bucket")));
        when(this.repository.findRootsWithChildren()).thenAnswer(call -> new ArrayList<>(this.table.get()));
    }

    @Test
    void anEditOnOneInstanceIsServedByTheOtherAtItsNextPoll() {
        LookupDataCacheService a = this.instance();
        LookupDataCacheService b = this.instance();

        this.table.set(Collections.singletonList(row("BUCKET_LIST", "etl-bucket-2")));
        a.changed();

        assertThat(a.getParentLookupById("BUCKET_LIST").getLookupValue()).as("the writer, at once").isEqualTo("etl-bucket-2");
        assertThat(b.getParentLookupById("BUCKET_LIST").getLookupValue()).as("B, before its poll").isEqualTo("etl-bucket");
        b.poll();
        assertThat(b.getParentLookupById("BUCKET_LIST").getLookupValue()).as("B, after its poll").isEqualTo("etl-bucket-2");
    }

    @Test
    void aDeletionOnOneInstanceLeavesNoGhostOnTheOther() {
        LookupDataCacheService a = this.instance();
        LookupDataCacheService b = this.instance();
        this.table.set(Collections.emptyList());
        a.changed();
        b.poll();
        assertThat(b.getParentLookupById("BUCKET_LIST")).isNull();
    }

    @Test
    void withNothingChangedAPollReadsNoTable() {
        LookupDataCacheService b = this.instance();
        b.poll();
        b.poll();
        verify(this.repository, times(1)).findRootsWithChildren();
    }

    /**
     * The two lookups that steer dispatch need no convergence window at all: they are read from the
     * table on every use, never from this cache. If one ever moves into the cache, it inherits the
     * poll bound, and this test says so.
     */
    @Test
    void theDispatchLookupsAreReadFromTheTableNotTheCache() throws IOException {
        List<String> offenders;
        try (Stream<Path> sources = Files.walk(Paths.get("src/main/java"))) {
            offenders = sources.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    String code = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    return code.matches("(?s).*getParentLookupById\\(\\s*(ProcessUtil\\.)?(QUEUE_FETCH_LIMIT|SCHEDULER_LAST_RUN_TIME).*");
                } catch (IOException unreadable) {
                    throw new IllegalStateException(unreadable);
                }
            }).map(Path::toString).collect(Collectors.toList());
        }
        assertThat(offenders).isEmpty();
    }
}
