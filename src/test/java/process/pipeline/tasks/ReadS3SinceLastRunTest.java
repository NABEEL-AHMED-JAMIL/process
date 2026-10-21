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

    /**
     * The mark is the job's for the prefix as the definition writes it: a run for one file ({{param.file}} filled with its
     * name) moves the mark the next run over the whole folder starts from.
     */
    @Test
    void theMarkIsKeptForThePrefixAsTheDefinitionWritesItNotAsOneRunFilledIt() throws Exception {
        object("in/a", 1);
        TaskContext oneFile = new TaskContext(config("bucket", "lake", "prefix", "in/a", "since", "last_run"), Dataset.EMPTY);
        oneFile.asWritten = config("bucket", "lake", "prefix", "in/{{param.file}}", "since", "last_run");
        oneFile.runId = 1;
        this.task.run(oneFile);
        assertThat(this.marks.prefixes).containsExactly("in/{{param.file}}");
    }

    /**
     * ZT-R18 (zero-tolerance QA 2026-10-20, Zanium's voice call insight): "Run with…" naming one recording
     * (file = synthetic-call-a.m4a) completed with nothing read, because an earlier run had read that file and the folder's
     * mark was past it. A run whose parameters name one object reads it, old or new; the mark never moves back.
     */
    @Test
    void aRunThatNamesOneFileReadsItEvenWhenAnEarlierRunReadIt() throws Exception {
        object("in/a.m4a", 1);
        object("in/b.m4a", 2);
        Map<String, Object> folder = config("bucket", "lake", "prefix", "in/", "since", "last_run");
        assertThat(keys(1, folder)).containsExactly("in/a.m4a", "in/b.m4a");
        this.marks.completed.add(1L);
        TaskContext oneFile = new TaskContext(config("bucket", "lake", "prefix", "in/a.m4a", "since", "last_run"), Dataset.EMPTY);
        oneFile.asWritten = config("bucket", "lake", "prefix", "in/{{param.file}}", "since", "last_run");
        oneFile.runId = 2;
        List<Object> read = new ArrayList<>();
        this.task.run(oneFile).getOutput().getRows().forEach(r -> read.add(r.get("key")));
        assertThat(read).as("the file the run names").containsExactly("in/a.m4a");
        assertThat(this.marks.byRun.get(2L).key).as("the mark stays where the folder's runs left it").isEqualTo("in/b.m4a");
        this.marks.completed.add(2L);
        assertThat(keys(3, folder)).as("the next run over the folder: nothing new").isEmpty();
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

    /** An inbox arrival's run reads its own file; a scheduled run of the same pipeline reads the prefix since the last run. */
    @Test
    void theRunsOwnFilesComeFirstWhenAskedAndMoveNoMark() throws Exception {
        object("intake/2026/10/20/x-new.m4a", 5);
        TaskContext arrival = new TaskContext(config("bucket", "lake", "prefix", "in/", "since", "last_run", "runFiles", true), Dataset.EMPTY);
        arrival.inputBucket = "inbox";
        arrival.inputKey = "intake/2026/10/20/x-new.m4a";
        List<Map<String, Object>> rows = this.task.run(arrival).getOutput().getRows();
        assertThat(rows).extracting(r -> r.get("key")).containsExactly("intake/2026/10/20/x-new.m4a");
        assertThat(rows.get(0).get("size")).isEqualTo(10L);
        assertThat(this.marks.byRun).isEmpty();

        object("in/a.m4a", 1);
        assertThat(keys(2, config("bucket", "lake", "prefix", "in/", "since", "last_run", "runFiles", true)))
            .as("a run no file started reads the prefix").contains("in/a.m4a");
    }

    @Test
    void storagesTimesAreRead() {
        assertThat(BucketStore.Listed.instant("2026-10-20T15:04:05Z")).isEqualTo(Instant.parse("2026-10-20T15:04:05Z"));
        assertThat(BucketStore.Listed.instant("2026-10-20T15:04:05.120+00:00")).isEqualTo(Instant.parse("2026-10-20T15:04:05.120Z"));
        assertThat(BucketStore.Listed.instant("not a time")).isNull();
        assertThat(BucketStore.Listed.instant(null)).isNull();
    }
}
