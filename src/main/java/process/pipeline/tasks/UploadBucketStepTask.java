package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.RunOutput;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.Streams;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.io.BufferedOutputStream;

/**
 * Upload to bucket (MIG-231): the step's input as a CSV, JSON or JSON Lines object in one of the workspace's own
 * buckets, through storage-service (which announces the upload and meters nothing here). The key takes the run's
 * placeholders, so each run can write its own object: exports/{{date}}/claims-{{run}}.csv. The rows pass on.
 *
 * MIG-344: streamed -- the file is spooled to the try's scratch directory (never held in memory), then uploaded from
 * there with its size, which storage-service needs and meters.
 */
@Component
public class UploadBucketStepTask extends RegisteredTask implements StreamingStepTask {

    static final TaskSpec SPEC = TaskSpec.builder("upload_bucket", "Upload to bucket", TaskKind.OUTPUT)
        .repeatSafe(true)  // a repeat writes the same thing again (owner 2026-10-21: passing errors are tried again)
        .description("Writes the rows as a CSV, JSON or JSON Lines object in a workspace bucket (storage-service).")
        .input(TaskSpec.rows("The rows to upload."))
        .output(TaskSpec.rows("The input, unchanged."))
        .config(FileConfigs.bucket(JsonSchema.object())
            .required("key", JsonSchema.string().minLength(1).maxLength(1024).title("Object key").format("template")
                .description("Where to write it; {{run}}, {{date}}, {{time}}, {{pipeline}} and the other run placeholders are filled in "
                    + "(one file per row: the row's columns too)."))
            .property("format", JsonSchema.string().enumOf("csv", "json", "jsonl").title("Format").defaultValue("csv"))
            .property("perRow", JsonSchema.bool().title("One file per row").defaultValue(false)
                .description("Each row is an object of its own (a JSON file is the row's object), its key filled from the row's "
                    + "columns: insights/{{file_stem}}.json. A row's values cannot add folders."))
            .property("maxFiles", JsonSchema.integer().minimum(1).maximum(Limits.MAX_OBJECTS).title("At most files").defaultValue(100)
                .description("One file per row: an input with more rows fails before any object is written."))
            .property("jsonColumns", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(255).format("column"))
                .maxItems(Limits.MAX_COLUMNS).title("JSON columns")
                .description("One file per row: columns holding JSON text (an AI answer's lists), written as lists and objects.")))
        .backing(TaskSpec.STORAGE)
        .retry(3, 10)
        .timeoutSeconds(600)
        .requiredRole(TaskSpec.ROLE_ADMIN)
        .aiToolName("upload_to_bucket")
        .items(ItemRole.EACH).itemsWhen("perRow", true).build();

    private final BucketStore buckets;

    public UploadBucketStepTask(BucketStore buckets) {
        super(SPEC);
        this.buckets = buckets;
    }

    @Override
    public Optional<String> unavailable() {
        return this.buckets.unavailable();
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        String bucket = Configs.text(context.config(), "bucket", null);
        String format = Configs.text(context.config(), "format", "csv");
        try (RowSource input = context.openInput()) {
            long rows = input.size();
            Map<String, Object> runValues = Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, rows));
            if (Configs.bool(context.config(), "perRow", false)) {
                return this.perRow(context, input, bucket, format, runValues);
            }
            String key = Templates.fill(Configs.text(context.config(), "key", null), runValues);
            if (key.startsWith("/") || key.contains("..") || key.contains("{{")) {
                throw new IllegalArgumentException(String.format("'%s' is not an object key a step writes: no leading '/', no '..', "
                    + "no unfilled placeholder.", key));
            }
            Path spool = context.scratch().resolve("upload." + format);
            MessageDigest digest = Streams.sha256();
            long size;
            try (OutputStream file = Files.newOutputStream(spool);
                 Streams.Bounded bounded = new Streams.Bounded(file, context.inMemory() ? Long.MAX_VALUE : context.maxFileBytes(), "The file");
                 OutputStream out = new DigestOutputStream(new BufferedOutputStream(bounded, 1 << 16), digest)) {
                FileFormats.writeTo(input, format, out);
                out.flush();
                size = bounded.count();
            }
            if (context.inMemory()) {
                Limits.requireBytes(size, "The file");
            }
            try (InputStream content = Files.newInputStream(spool)) {
                this.buckets.upload(context.tenantId(), bucket, key, content, size, FileFormats.contentType(format));
            } finally {
                Files.deleteIfExists(spool);
            }
            context.recordOutput(RunOutput.bucket(bucket, key, format, rows, size).withSha256(Streams.hex(digest.digest())));
            context.log(String.format("Uploaded %d row(s) to %s/%s (%s, %,d bytes).", rows, bucket, key, format, size));
            return StepResult.nothing(rows);
        }
    }

    /** One object a row, its key from the row's columns (made safe), at most maxFiles; a key taken twice gets -2, -3. */
    private StepResult perRow(StreamContext context, RowSource input, String bucket, String format, Map<String, Object> runValues)
        throws Exception {
        long rows = input.size();
        int maxFiles = Configs.integer(context.config(), "maxFiles", 100);
        if (rows > maxFiles) {
            throw new IllegalStateException(String.format("The input has %,d rows; this step writes at most %d objects (maxFiles).", rows, maxFiles));
        }
        String template = Configs.text(context.config(), "key", null);
        List<String> json = Configs.texts(context.config(), "jsonColumns");
        List<String> columns = input.columns();
        Set<String> taken = new HashSet<>();
        Path spool = context.scratch().resolve("upload-row." + format);
        int number = 0;
        long bytes = 0;
        try {
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                for (Map<String, Object> row : batch) {
                    number++;
                    String key = FileRows.unique(FileRows.checkedKey(FileRows.fillForRow(template, runValues, row, number)), taken);
                    MessageDigest digest = Streams.sha256();
                    try (OutputStream out = new DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(spool), 1 << 16), digest)) {
                        FileFormats.writeRow(columns, row, format, json, out);
                    }
                    long size = Files.size(spool);
                    Limits.requireBytes(size, "The file");
                    try (InputStream content = Files.newInputStream(spool)) {
                        this.buckets.upload(context.tenantId(), bucket, key, content, size, FileFormats.contentType(format));
                    }
                    // MIG-420: the row's object is its item's.
                    context.recordOutput(RunOutput.bucket(bucket, key, format, 1, size).withSha256(Streams.hex(digest.digest())), row);
                    bytes += size;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Upload to bucket was stopped.");
                }
            }
        } finally {
            Files.deleteIfExists(spool);
        }
        context.log(String.format("Uploaded %d row(s) as %d object(s) in %s (%s, %,d bytes).", rows, number, bucket, format, bytes));
        return StepResult.nothing(rows);
    }
}
