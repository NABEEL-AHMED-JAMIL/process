package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.FileFormats;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.Optional;

/**
 * Upload to bucket (MIG-231): the step's input as a CSV, JSON or JSON Lines object in one of the workspace's own
 * buckets, through storage-service (which announces the upload and meters nothing here). The key takes the run's
 * placeholders, so each run can write its own object: exports/{{date}}/claims-{{run}}.csv. The rows pass on.
 */
@Component
public class UploadBucketStepTask extends RegisteredTask {

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
    public StepResult run(StepContext context) throws Exception {
        String bucket = Configs.text(context.config(), "bucket", null);
        String format = Configs.text(context.config(), "format", "csv");
        Dataset input = context.input();
        String key = Templates.fill(Configs.text(context.config(), "key", null), Templates.ofRun(context, input.size()));
        if (key.startsWith("/") || key.contains("..") || key.contains("{{")) {
            throw new IllegalArgumentException(String.format("'%s' is not an object key a step writes: no leading '/', no '..', "
                + "no unfilled placeholder.", key));
        }
        byte[] content = FileFormats.write(input, format);
        this.buckets.upload(context.tenantId(), bucket, key, content, FileFormats.contentType(format));
        context.log(String.format("Uploaded %d row(s) to %s/%s (%s, %,d bytes).", input.size(), bucket, key, format, content.length));
        return StepResult.nothing((long) input.size());
    }
}
