package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.backing.Fakes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.data.Limits;
import process.pipeline.data.FileFormatsStreamTest;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Read S3 lists a prefix, or reads every object under it as rows, in the run's workspace. */
class ReadS3StepTaskTest {

    private final Fakes.Buckets buckets = new Fakes.Buckets();
    private final ReadS3StepTask task = new ReadS3StepTask(this.buckets);

    ReadS3StepTaskTest() {
        this.buckets.objects.put("lake/in/a.csv", "id,amount\n1,10\n2,20\n".getBytes(StandardCharsets.UTF_8));
        this.buckets.objects.put("lake/in/b.jsonl", "{\"id\":3,\"amount\":30}\n".getBytes(StandardCharsets.UTF_8));
        this.buckets.objects.put("lake/other/c.csv", "id\n9\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listingIsOneRowPerObjectUnderThePrefix() throws Exception {
        Dataset out = this.task.run(TaskContext.of(config("bucket", "lake", "prefix", "in/"), Collections.emptyList())).getOutput();
        assertThat(out.getRows()).containsExactly(row("key", "in/a.csv", "size", 20L), row("key", "in/b.jsonl", "size", 21L));
        assertThat(this.buckets.lastTenant).isEqualTo(TaskContext.TENANT);
    }

    @Test
    void readingEveryObjectTagsEachRowWithItsKey() throws Exception {
        TaskContext context = TaskContext.of(config("bucket", "lake", "prefix", "in/", "format", "auto"), Collections.emptyList());
        Dataset out = this.task.run(context).getOutput();
        assertThat(out.getRows()).containsExactly(
            row("id", "1", "amount", "10", "_source_key", "in/a.csv"),
            row("id", "2", "amount", "20", "_source_key", "in/a.csv"),
            row("id", 3L, "amount", 30L, "_source_key", "in/b.jsonl"));
        assertThat(context.lines).anyMatch(line -> line.contains("3 row(s) from 2 object(s)"));
    }

    /**
     * ZT-S3 (showcase job 36): Read S3 streams its objects one after another into the step's output -- a Parquet one a row
     * group at a time -- so a prefix's rows go past the in-memory bound; each row keeps its _source_key; maxRows stops it.
     */
    @Test
    void objectsStreamPastTheInMemoryBound(@TempDir Path dir) throws Exception {
        this.buckets.objects.put("lake/big/a.parquet", FileFormatsStreamTest.parquet(dir, Limits.MAX_ROWS + 10_000));
        this.buckets.objects.put("lake/big/b.csv", "id,ward\n1,W1\n2,W2\n".getBytes(StandardCharsets.UTF_8));
        StreamTry read = new StreamTry(config("bucket", "lake", "prefix", "big/", "format", "auto"), new Dataset(Collections.emptyList(),
            Collections.emptyList()), 64L << 20, dir, 1000);
        assertThat(this.task.stream(read).getRecordsOut()).isEqualTo(Limits.MAX_ROWS + 10_002L);
        Map<String, Object> last = read.rows.get(read.rows.size() - 1);
        assertThat(last).containsEntry("_source_key", "big/b.csv").containsEntry("ward", "W2");
        assertThat(read.rows.get(0)).containsEntry("_source_key", "big/a.parquet");

        StreamTry few = new StreamTry(config("bucket", "lake", "prefix", "big/", "format", "auto", "maxRows", 7), new Dataset(Collections.emptyList(),
            Collections.emptyList()), 64L << 20, dir, 1000);
        assertThat(this.task.stream(few).getRecordsOut()).isEqualTo(7L);
    }

    @Test
    void aLimitBelowTheObjectsWarns() throws Exception {
        TaskContext context = TaskContext.of(config("bucket", "lake", "prefix", "in/", "limit", 1), Collections.emptyList());
        assertThat(this.task.run(context).getOutput().size()).isEqualTo(1);
        assertThat(context.lines).anyMatch(line -> line.startsWith("WARN More than 1 objects"));
    }
}
