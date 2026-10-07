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
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Optional;
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
        .description("Writes the rows as a CSV, JSON or JSON Lines object in a workspace bucket (storage-service).")
        .input(TaskSpec.rows("The rows to upload."))
        .output(TaskSpec.rows("The input, unchanged."))
        .config(FileConfigs.bucket(JsonSchema.object())
            .required("key", JsonSchema.string().minLength(1).maxLength(1024).title("Object key").format("template")
                .description("Where to write it; {{run}}, {{date}}, {{time}}, {{pipeline}} and the other run placeholders are filled in."))
            .property("format", JsonSchema.string().enumOf("csv", "json", "jsonl").title("Format").defaultValue("csv")))
        .backing(TaskSpec.STORAGE)
        .retry(3, 10)
        .timeoutSeconds(600)
        .requiredRole(TaskSpec.ROLE_ADMIN)
        .aiToolName("upload_to_bucket")
        .build();

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
            String key = Templates.fill(Configs.text(context.config(), "key", null),
                Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, rows)));
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
}
