package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.data.RowCollector;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import process.pipeline.backing.ObjectReadMarks;
import process.pipeline.DefinitionProblem;
import java.util.List;

/**
 * Read S3 (MIG-231): the objects under a prefix of one of the workspace's buckets, through storage-service. As
 * {@code list} (the default), one row per object -- key and size; as a file format, every object's rows one after
 * another, each with its {@code _source_key}. At most {@code limit} objects ({@value Limits#MAX_OBJECTS}), and the
 * whole within the step's bounds.
 */
@Component
public class ReadS3StepTask extends RegisteredTask {

    static final String SINCE_ALL = "all";
    static final String SINCE_LAST_RUN = "last_run";

    static final TaskSpec SPEC = TaskSpec.builder("read_s3", "Read S3", TaskKind.READ)
        .description("Lists the objects under a prefix of a workspace bucket, or reads all of them as rows (storage-service).")
        .output(TaskSpec.rows("list: key, size per object. A file format: each object's rows, with _source_key."))
        .config(FileConfigs.parsing(FileConfigs.bucket(JsonSchema.object())
            .property("prefix", JsonSchema.string().maxLength(1024).title("Prefix").defaultValue("")
                .description("Only objects whose keys start with this; {{date}} and the other run placeholders are filled in."))
            .property("format", JsonSchema.string().enumOf("list", "auto", "csv", "json", "jsonl", "parquet").title("Read as")
                .defaultValue("list").description("list: one row per object. Otherwise each object's rows (auto: by its extension)."))
            .property("limit", JsonSchema.integer().minimum(1).maximum(Limits.MAX_OBJECTS).title("At most objects").defaultValue(100))
            .property("since", JsonSchema.string().enumOf(SINCE_ALL, SINCE_LAST_RUN).title("Which objects").defaultValue(SINCE_ALL)
                .description("all, or only the objects written since this job's last completed run read the prefix (oldest first; "
                    + "a failed run moves nothing, so its files come again)."))))
        .backing(TaskSpec.STORAGE)
        .retry(3, 10)
        .timeoutSeconds(600)
        .aiToolName("read_s3")
        .build();

    private final BucketStore buckets;
    private final ObjectReadMarks marks;

    public ReadS3StepTask(BucketStore buckets) {
        this(buckets, null);
    }

    @Autowired
    public ReadS3StepTask(BucketStore buckets, ObjectReadMarks marks) {
        super(SPEC);
        this.buckets = buckets;
        this.marks = marks;
    }

    @Override
    public Optional<String> unavailable() {
        return this.buckets.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        return FileConfigs.checkColumns(config);
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        String bucket = Configs.text(config, "bucket", null);
        String prefix = Templates.fill(Configs.text(config, "prefix", ""), Templates.ofRun(context, null));
        String format = Configs.text(config, "format", "list");
        int limit = Configs.integer(config, "limit", 100);
        boolean sinceLastRun = SINCE_LAST_RUN.equals(Configs.text(config, "since", SINCE_ALL));
        BucketStore.Listing listing;
        ObjectReadMarks.Mark upTo = null;
        if (sinceLastRun) {
            Fresh fresh = this.newSinceLastRun(context, bucket, prefix, Configs.text(config, "prefix", ""), limit);
            listing = fresh.listing;
            upTo = fresh.upTo;
        } else {
            listing = this.buckets.list(context.tenantId(), bucket, prefix, limit);
            if (listing.truncated) {
                context.warn(String.format("More than %d objects under '%s'; only the first %d are read.", limit, prefix, limit));
            }
        }
        StepResult result = this.read(context, config, bucket, prefix, format, listing);
        if (sinceLastRun && context.jobId() != null && this.marks != null) {
            this.marks.record(context.jobQueueId(), context.stepKey(), context.jobId(), bucket, Configs.text(config, "prefix", ""), upTo,
                listing.objects.size(), context.attempt());
        }
        return result;
    }

    /** The new objects a try reads, and the newest of them (or the mark it started from, when none is new). */
    private static final class Fresh {
        final BucketStore.Listing listing;
        final ObjectReadMarks.Mark upTo;

        Fresh(BucketStore.Listing listing, ObjectReadMarks.Mark upTo) {
            this.listing = listing;
            this.upTo = upTo;
        }
    }

    /**
     * The objects written after the newest one this job's last completed run read (with this step, bucket and the prefix as
     * written in the step),
     * oldest first, at most {@code limit} -- the rest wait for the next run. The whole prefix is listed (at most
     * {@value Limits#MAX_OBJECTS} objects; more fail the step, since an unlisted object could be older than the mark and
     * then never read).
     */
    private Fresh newSinceLastRun(StepContext context, String bucket, String prefix, String asWritten, int limit) throws Exception {
        // The mark is the job's for the prefix as the step writes it ({{date}}/, {{param.file}}): a run for one day or one
        // file moves it, so the next run reads only what is newer still, whatever its placeholders fill in.
        Optional<ObjectReadMarks.Mark> mark = context.jobId() == null || this.marks == null ? Optional.<ObjectReadMarks.Mark>empty()
            : this.marks.watermark(context.jobId(), context.stepKey(), bucket, asWritten);
        BucketStore.Listing all = this.buckets.list(context.tenantId(), bucket, prefix, Limits.MAX_OBJECTS);
        if (all.truncated) {
            throw new IllegalStateException(String.format("More than %,d objects under %s/%s: \"only new files since the last run\" lists the "
                + "whole prefix, so narrow it (a folder of its own, or a dated one).", Limits.MAX_OBJECTS, bucket, prefix));
        }
        List<BucketStore.Listed> fresh = new ArrayList<>();
        int undated = 0;
        for (BucketStore.Listed object : all.objects) {
            if (object.lastModified == null) {
                undated++;
                continue;
            }
            if (!mark.isPresent() || new ObjectReadMarks.Mark(object.lastModified, object.key).compareTo(mark.get()) > 0) {
                fresh.add(object);
            }
        }
        fresh.sort(Comparator.comparing((BucketStore.Listed o) -> o.lastModified).thenComparing(o -> o.key));
        boolean more = fresh.size() > limit;
        List<BucketStore.Listed> taken = more ? new ArrayList<>(fresh.subList(0, limit)) : fresh;
        BucketStore.Listed newest = taken.isEmpty() ? null : taken.get(taken.size() - 1);
        ObjectReadMarks.Mark upTo = newest == null ? mark.orElse(null) : new ObjectReadMarks.Mark(newest.lastModified, newest.key);
        if (context.jobId() == null || this.marks == null) {
            context.log("Not a job's run: every object under the prefix is new.");
        } else {
            context.log(mark.isPresent() ? String.format("%d object(s) written since the last completed run read %s/%s (after %s, %s).",
                fresh.size(), bucket, prefix, mark.get().modified, mark.get().key)
                : String.format("No earlier completed run read %s/%s: every object is new (%d).", bucket, prefix, fresh.size()));
        }
        if (more) {
            context.warn(String.format("%d new objects; the oldest %d are read now, the rest by the next run.", fresh.size(), limit));
        }
        if (undated > 0) {
            context.warn(String.format("%d object(s) have no last-modified time from storage and are not read in this mode.", undated));
        }
        return new Fresh(new BucketStore.Listing(taken, more), upTo);
    }

    private StepResult read(StepContext context, Map<String, Object> config, String bucket, String prefix, String format,
        BucketStore.Listing listing) throws Exception {
        RowCollector rows = new RowCollector(String.format("%s/%s", bucket, prefix), Configs.integer(config, "maxRows", null));
        if ("list".equals(format)) {
            for (BucketStore.Listed object : listing.objects) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", object.key);
                row.put("size", object.size);
                rows.add(row);
            }
            context.log(String.format("%d object(s) under %s/%s.", rows.size(), bucket, prefix));
            return StepResult.of(rows.toDataset());
        }
        long bytes = 0;
        int files = 0;
        for (BucketStore.Listed object : listing.objects) {
            if (rows.full()) {
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Read S3 was stopped.");
            }
            bytes += object.size;
            Limits.requireBytes(bytes, String.format("The objects under %s/%s", bucket, prefix));
            byte[] content = this.buckets.read(context.tenantId(), bucket, object.key, Limits.MAX_FILE_BYTES);
            RowCollector one = new RowCollector(object.key, null);
            FileFormats.read(content, FileConfigs.formatOf(format, object.key), FileConfigs.options(config), one);
            for (Map<String, Object> row : one.toDataset().getRows()) {
                Map<String, Object> tagged = new LinkedHashMap<>(row);
                tagged.put("_source_key", object.key);
                rows.add(tagged);
                if (rows.full()) {
                    break;
                }
            }
            files++;
        }
        context.log(String.format("%d row(s) from %d object(s) under %s/%s.", rows.size(), files, bucket, prefix));
        return StepResult.of(rows.toDataset());
    }
}
