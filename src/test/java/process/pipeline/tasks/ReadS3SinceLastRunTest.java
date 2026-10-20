package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.backing.BucketStore;
import process.pipeline.backing.ObjectReadMarks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;

/**
 * read_s3 "only new files since the last run": a scheduled run reads the objects written after the newest one this job's
 * last completed run read -- oldest first, at most limit, the rest left for the next run -- and records how far it read.
 * Not a job's run (a preview) reads everything and records nothing.
 */
class ReadS3SinceLastRunTest {

    private static final Instant T0 = Instant.parse("2026-10-20T10:00:00Z");

    /** A bucket that lists objects with their times. */
    static final class TimedBuckets implements BucketStore {
        final List<Listed> objects = new ArrayList<>();
        boolean truncated;

        @Override public Optional<String> unavailable() { return Optional.empty(); }

        @Override
        public Listing list(long tenantId, String bucket, String prefix, int limit) {
            return new Listing(new ArrayList<>(this.objects), this.truncated);
        }

        @Override public byte[] read(long tenantId, String bucket, String key, long maxBytes) { throw new UnsupportedOperationException(); }

        @Override public void upload(long tenantId, String bucket, String key, byte[] content, String contentType) { }
    }

    /** The marks as the table keeps them: per run; the watermark is the newest a "completed" run recorded. */
    static final class Marks implements ObjectReadMarks {
        final Map<Long, Mark> byRun = new LinkedHashMap<>();
        final List<Long> completed = new ArrayList<>();
        final List<String> prefixes = new ArrayList<>();

        @Override
        public Optional<Mark> watermark(long jobId, String stepKey, String bucket, String prefix) {
            return this.byRun.entrySet().stream().filter(e -> this.completed.contains(e.getKey()) && e.getValue() != null)
                .map(Map.Entry::getValue).max(Mark::compareTo);
        }

        @Override
        public void record(long jobQueueId, String stepKey, long jobId, String bucket, String prefix, Mark upTo, long objects, int attempt) {
            this.byRun.put(jobQueueId, upTo);
            this.prefixes.add(prefix);
        }
    }

    private final TimedBuckets buckets = new TimedBuckets();
    private final Marks marks = new Marks();
    private final ReadS3StepTask task = new ReadS3StepTask(this.buckets, this.marks);

    private void object(String key, int minutes) {
        this.buckets.objects.add(new BucketStore.Listed(key, 10, T0.plusSeconds(60L * minutes)));
    }

    private List<Object> keys(long run, Map<String, Object> config) throws Exception {
        TaskContext t = new TaskContext(config, Dataset.EMPTY);
        t.runId = run;
        List<Object> out = new ArrayList<>();
        this.task.run(t).getOutput().getRows().forEach(r -> out.add(r.get("key")));
        return out;
    }

    @Test
    void eachCompletedRunMovesTheMarkAndAFailedOneMovesNothing() throws Exception {
        Map<String, Object> config = config("bucket", "lake", "prefix", "in/", "since", "last_run");
        object("in/b.m4a", 2);
        object("in/a.m4a", 1);
        assertThat(keys(1, config)).as("the first run: every object, oldest first").containsExactly("in/a.m4a", "in/b.m4a");
        this.marks.completed.add(1L);

        object("in/c.m4a", 3);
        assertThat(keys(2, config)).containsExactly("in/c.m4a");
        // Run 2 failed: its mark is not the job's.
        object("in/d.m4a", 4);
        assertThat(keys(3, config)).as("a failed run's files come again").containsExactly("in/c.m4a", "in/d.m4a");
        this.marks.completed.add(3L);
        assertThat(keys(4, config)).isEmpty();
        assertThat(this.marks.byRun.get(4L).key).as("a run that read nothing keeps the mark").isEqualTo("in/d.m4a");
    }

    @Test
    void atMostLimitAreReadOldestFirstAndTheRestWaitForTheNextRun() throws Exception {
        Map<String, Object> config = config("bucket", "lake", "prefix", "in/", "since", "last_run", "limit", 2);
        object("in/z.wav", 1);
        object("in/y.wav", 2);
        object("in/x.wav", 3);
        assertThat(keys(1, config)).containsExactly("in/z.wav", "in/y.wav");
        this.marks.completed.add(1L);
        assertThat(keys(2, config)).containsExactly("in/x.wav");
    }

    @Test
    void objectsWrittenInTheSameInstantAreOrderedByKey() throws Exception {
        Map<String, Object> config = config("bucket", "lake", "prefix", "in/", "since", "last_run", "limit", 1);
        object("in/b", 1);
        object("in/a", 1);
        assertThat(keys(1, config)).containsExactly("in/a");
        this.marks.completed.add(1L);
        assertThat(keys(2, config)).containsExactly("in/b");
    }

    /** The mark is the job's for the prefix as written: a run for one file ({{param.file}}) moves it for the next. */
    @Test
    void theMarkIsKeptForThePrefixAsTheStepWritesIt() throws Exception {
        object("in/a", 1);
        keys(1, config("bucket", "lake", "prefix", "in/{{param.file}}", "since", "last_run"));
        assertThat(this.marks.prefixes).containsExactly("in/{{param.file}}");
    }

    @Test
    void aPrefixTooBigToListWholeIsRefused() {
        this.buckets.truncated = true;
        assertThatThrownBy(() -> keys(1, config("bucket", "lake", "prefix", "in/", "since", "last_run")))
            .hasMessageContaining("narrow it");
    }

    @Test
    void theDefaultStillReadsEveryObjectAndRecordsNothing() throws Exception {
        object("in/a", 1);
        assertThat(keys(1, config("bucket", "lake", "prefix", "in/"))).containsExactly("in/a");
        assertThat(this.marks.byRun).isEmpty();
    }

    @Test
    void storagesTimesAreRead() {
        assertThat(BucketStore.Listed.instant("2026-10-20T15:04:05Z")).isEqualTo(Instant.parse("2026-10-20T15:04:05Z"));
        assertThat(BucketStore.Listed.instant("2026-10-20T15:04:05.120+00:00")).isEqualTo(Instant.parse("2026-10-20T15:04:05.120Z"));
        assertThat(BucketStore.Listed.instant("not a time")).isNull();
        assertThat(BucketStore.Listed.instant(null)).isNull();
    }
}
