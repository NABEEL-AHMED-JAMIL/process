package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import process.pipeline.StepContext;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.RowSink;
import process.pipeline.StepResult;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.data.RowCollector;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.ItemRole;
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
import java.util.HashSet;
import java.util.Set;
import process.pipeline.backing.ReadTakes;

/**
 * Read S3 (MIG-231): the objects under a prefix of one of the workspace's buckets, through storage-service. As
 * {@code list} (the default), one row per object -- key and size; as a file format, every object's rows one after
 * another, each with its {@code _source_key}. At most {@code limit} objects ({@value Limits#MAX_OBJECTS}), and the
 * whole within the step's bounds. ZT-S3: it streams (StreamingStepTask): every object's rows go into the step's output as
 * they are read, a CSV, JSON Lines or Parquet object without being held whole.
 */
@Component
public class ReadS3StepTask extends RegisteredTask implements StreamingStepTask {

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
            .property("runFiles", JsonSchema.bool().title("The run's own files first").defaultValue(false)
                .description("A run started for files (an inbox arrival) reads those files instead of the prefix; any other run "
                    + "reads the prefix. So one pipeline serves an inbox trigger and a schedule."))
            .property("since", JsonSchema.string().enumOf(SINCE_ALL, SINCE_LAST_RUN).title("Which objects").defaultValue(SINCE_ALL)
                .description("all, or only the objects written since this job's last completed run read the prefix (oldest first; "
                    + "a failed run moves nothing, so its files come again)."))))
        .backing(TaskSpec.STORAGE)
        .retry(3, 10)
        .timeoutSeconds(600)
        .aiToolName("read_s3")
        .items(ItemRole.SOURCE).itemsWhen("format", "list").itemIdentity("key").build();

    private final BucketStore buckets;
    private final ObjectReadMarks marks;
    private ReadTakes takes;

    public ReadS3StepTask(BucketStore buckets) {
        this(buckets, null);
    }

    /** Owner 2026-10-21: which run took which object, so runs side by side (a workflow's requests) never read the same one. */
    @Autowired(required = false)
    public void useTakes(ReadTakes takes) {
        this.takes = takes;
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
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        String bucket = Configs.text(config, "bucket", null);
        String prefix = Templates.fill(Configs.text(config, "prefix", ""), Templates.ofRun(context, null));
        String format = Configs.text(config, "format", "list");
        int limit = Configs.integer(config, "limit", 100);
        if (Configs.bool(config, "runFiles", false) && !context.inputKeys().isEmpty()) {
            return this.runFiles(context, config, bucket, format);
        }
        boolean sinceLastRun = SINCE_LAST_RUN.equals(Configs.text(config, "since", SINCE_ALL));
        BucketStore.Listing listing;
        ObjectReadMarks.Mark upTo = null;
        if (sinceLastRun) {
            Fresh fresh = this.newSinceLastRun(context, bucket, prefix, asWritten(context), limit);
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
            this.marks.record(context.jobQueueId(), context.stepKey(), context.jobId(), bucket, asWritten(context), upTo,
                listing.objects.size(), context.attempt());
        }
        return result;
    }

    /** The prefix as the definition writes it: {{param.file}} and {{run.date}} unfilled, so one mark serves every run. */
    static String asWritten(StepContext context) {
        Map<String, Object> written = context.configAsWritten();
        return Configs.text(written == null ? context.config() : written, "prefix", "");
    }

    /** An object as run_read_take keeps it: a rewritten object (a later time) is a new one. */
    static String item(BucketStore.Listed object) {
        return object.lastModified.toEpochMilli() + ":" + object.key;
    }

    /** The files the run was started for, in its own bucket (the inbox), instead of the prefix; no watermark moves. */
    private StepResult runFiles(StreamContext context, Map<String, Object> config, String bucket, String format) throws Exception {
        String own = context.inputBucket() == null ? bucket : context.inputBucket();
        List<BucketStore.Listed> objects = new ArrayList<>();
        for (String key : context.inputKeys()) {
            BucketStore.Listed found = null;
            for (BucketStore.Listed listed : this.buckets.list(context.tenantId(), own, key, 1).objects) {
                if (listed.key.equals(key)) {
                    found = listed;
                }
            }
            objects.add(found != null ? found : new BucketStore.Listed(key, 0));
        }
        context.log(String.format("The run was started for %d file(s) in %s: they are read instead of the prefix.", objects.size(), own));
        return this.read(context, config, own, "", format, new BucketStore.Listing(objects, false));
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
        // ZT-R18: a run whose parameters fill the prefix to one object's key ("Run with…" a recording) asked for that file:
        // it is read whether or not an earlier run read it.
        boolean named = !prefix.equals(asWritten) && !prefix.isEmpty() && !prefix.endsWith("/");
        BucketStore.Listed asked = null;
        for (BucketStore.Listed object : all.objects) {
            if (named && object.key.equals(prefix)) {
                asked = object;
                continue;
            }
            if (object.lastModified == null) {
                undated++;
                continue;
            }
            if (!mark.isPresent() || new ObjectReadMarks.Mark(object.lastModified, object.key).compareTo(mark.get()) > 0) {
                fresh.add(object);
            }
        }
        fresh.sort(Comparator.comparing((BucketStore.Listed o) -> o.lastModified).thenComparing(o -> o.key));
        boolean more;
        List<BucketStore.Listed> taken;
        if (context.jobId() != null && this.takes != null) {
            // Runs side by side (a workflow's requests) start from the same mark: each takes the objects no run in flight
            // took, and a failed run's objects -- behind the mark or not -- come again.
            List<BucketStore.Listed> dated = new ArrayList<>();
            for (BucketStore.Listed object : all.objects) {
                if (object.lastModified != null && !(named && object.key.equals(prefix))) {
                    dated.add(object);
                }
            }
            dated.sort(Comparator.comparing((BucketStore.Listed o) -> o.lastModified).thenComparing(o -> o.key));
            Map<String, BucketStore.Listed> byItem = new LinkedHashMap<>();
            for (BucketStore.Listed object : dated) {
                byItem.put(item(object), object);
            }
            Set<String> freshItems = new HashSet<>();
            for (BucketStore.Listed object : fresh) {
                freshItems.add(item(object));
            }
            ReadTakes.Taken took = this.takes.take(context.jobQueueId(), context.jobId(), context.stepKey(), "s3|" + bucket + "|" + asWritten,
                new ArrayList<>(byItem.keySet()), freshItems, limit);
            taken = new ArrayList<>();
            for (String item : took.items) {
                taken.add(byItem.get(item));
            }
            more = took.more;
            int again = taken.size() - (int) taken.stream().filter(o -> freshItems.contains(item(o))).count();
            if (took.heldByOthers > 0) {
                context.log(String.format("%d new object(s) are being read by another run of this job; they are left to it.", took.heldByOthers));
            }
            if (again > 0) {
                context.log(String.format("%d object(s) a run that did not complete had taken are read again.", again));
            }
        } else {
            more = fresh.size() > limit;
            taken = more ? new ArrayList<>(fresh.subList(0, limit)) : fresh;
        }
        BucketStore.Listed newest = taken.isEmpty() ? null : taken.get(taken.size() - 1);
        ObjectReadMarks.Mark upTo = newest == null ? mark.orElse(null) : new ObjectReadMarks.Mark(newest.lastModified, newest.key);
        if (upTo != null && mark.isPresent() && mark.get().compareTo(upTo) > 0) {
            upTo = mark.get();  // an object read again is behind the mark; the mark never moves back
        }
        if (asked != null) {
            taken = new ArrayList<>(taken);
            taken.add(0, asked);
            if (asked.lastModified != null) {
                ObjectReadMarks.Mark own = new ObjectReadMarks.Mark(asked.lastModified, asked.key);
                upTo = upTo == null || own.compareTo(upTo) > 0 ? own : upTo;  // the mark never moves back
            }
            context.log(String.format("The run names %s: it is read, whether or not an earlier run read it.", asked.key));
        }
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

    /**
     * The objects' rows into the step's output, one object after the other (ZT-S3: streamed -- a CSV, JSON Lines or Parquet
     * object row by row, Parquet a row group at a time -- so the prefix's rows are bounded by the step, not held in memory;
     * a JSON object, or every object on the in-memory path, is read whole). Each row keeps its {@code _source_key}; maxRows
     * stops the read.
     */
    /**
     * ZT-S8 (perf cycle, 2026-10-27): an object that cannot be read as its format names itself -- bucket, key and format, and the
     * parser's own words (its line) -- where the step's error said only "(line 159) invalid char ..." of one object among many.
     */
    static IllegalStateException unreadable(String bucket, String key, String format, Exception cause) {
        String why = cause.getMessage() == null || cause.getMessage().isBlank() ? cause.getClass().getSimpleName() : cause.getMessage().trim();
        return new IllegalStateException(String.format("%s/%s could not be read as %s: %s", bucket, key, format, why), cause);
    }

    private StepResult read(StreamContext context, Map<String, Object> config, String bucket, String prefix, String format,
        BucketStore.Listing listing) throws Exception {
        Integer maxRows = Configs.integer(config, "maxRows", null);
        long wanted = maxRows == null ? Long.MAX_VALUE : maxRows;
        RowSink out = context.output();
        if ("list".equals(format)) {
            out.declare(List.of("key", "size"));
            for (BucketStore.Listed object : listing.objects) {
                if (out.size() >= wanted) {
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", object.key);
                row.put("size", object.size);
                out.add(row);
            }
            context.log(String.format("%d object(s) under %s/%s.", out.size(), bucket, prefix));
            return StepResult.streamed(out.size());
        }
        long bytes = 0;
        int files = 0;
        for (BucketStore.Listed object : listing.objects) {
            if (out.size() >= wanted) {
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Read S3 was stopped.");
            }
            String objectFormat = FileConfigs.formatOf(format, object.key);
            RowSink tagged = new ReadFileStepTask.SourceKeyed(out, object.key);
            if (context.inMemory() || !FileFormats.streams(objectFormat)) {
                bytes += object.size;
                Limits.requireBytes(bytes, String.format("The objects under %s/%s", bucket, prefix));
                byte[] content = this.buckets.read(context.tenantId(), bucket, object.key, Limits.MAX_FILE_BYTES);
                RowCollector one = new RowCollector(object.key, null);
                try {
                    FileFormats.read(content, objectFormat, FileConfigs.options(config), one);
                } catch (IOException | UncheckedIOException | IllegalArgumentException unreadable) {
                    throw unreadable(bucket, object.key, objectFormat, unreadable);
                }
                for (Map<String, Object> row : one.toDataset().getRows()) {
                    if (out.size() >= wanted) {
                        break;
                    }
                    tagged.add(new LinkedHashMap<>(row));
                }
            } else {
                try (InputStream in = this.buckets.open(context.tenantId(), bucket, object.key, context.maxFileBytes())) {
                    FileFormats.readStream(in, objectFormat, FileConfigs.options(config), tagged, maxRows, context.maxRows(), object.key);
                } catch (IOException | UncheckedIOException | IllegalArgumentException unreadable) {
                    throw unreadable(bucket, object.key, objectFormat, unreadable);
                }
            }
            files++;
        }
        context.log(String.format("%d row(s) from %d object(s) under %s/%s.", out.size(), files, bucket, prefix));
        return StepResult.streamed(out.size());
    }
}
