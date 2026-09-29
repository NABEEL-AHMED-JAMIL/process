package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.data.RowCollector;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read CSV/JSON/Parquet (MIG-231): one object of a workspace bucket, through storage-service, as rows. CSV cells are
 * text (a Transform casts them); JSON is an array of objects, or an object with the rows at {@code rowsPath}; JSON
 * Lines one object per line; Parquet through DuckDB. At most {@value Limits#MAX_FILE_BYTES} bytes.
 *
 * With no bucket and key it reads the file the run was started for -- an inbox arrival's (MIG-239,
 * job_queue.input_bucket/input_key) -- so an inbox-triggered pipeline starts with "Read CSV/JSON/Parquet" and nothing
 * else; a run no file started fails the step and says so.
 */
@Component
public class ReadFileStepTask extends RegisteredTask {

    static final TaskSpec SPEC = TaskSpec.builder("read_file", "Read CSV/JSON/Parquet", TaskKind.READ)
        .description("Reads one file of a workspace bucket -- CSV, JSON, JSON Lines or Parquet -- as rows (storage-service).")
        .output(TaskSpec.rows("The file's rows; CSV cells as text."))
        .config(FileConfigs.parsing(JsonSchema.object()
            .property("bucket", JsonSchema.string().minLength(1).maxLength(255).title("Bucket").format("bucket")
                .description(FileConfigs.BUCKET_DESCRIPTION + " Empty, with the file empty too: the file the run was started for (inbox)."))
            .property("key", JsonSchema.string().minLength(1).maxLength(1024).title("File").format("template")
                .description("The object's key; {{date}} and the other run placeholders are filled in. Empty: the run's own file (inbox)."))
            .property("format", JsonSchema.string().enumOf("auto", "csv", "json", "jsonl", "parquet").title("Format")
                .defaultValue("auto").description("auto: by the key's extension."))))
        .backing(TaskSpec.STORAGE)
        .retry(3, 10)
        .timeoutSeconds(600)
        .aiToolName("read_file")
        .build();

    private final BucketStore buckets;

    public ReadFileStepTask(BucketStore buckets) {
        super(SPEC);
        this.buckets = buckets;
    }

    @Override
    public Optional<String> unavailable() {
        return this.buckets.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        if ((Configs.text(config, "bucket", null) == null) != (Configs.text(config, "key", null) == null)) {
            return Collections.singletonList(new DefinitionProblem(Configs.text(config, "bucket", null) == null ? "bucket" : "key",
                "name both the bucket and the file, or neither (the file the run was started for)"));
        }
        return Collections.emptyList();
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        String bucket = Configs.text(config, "bucket", null);
        String key = Templates.fill(Configs.text(config, "key", null), Templates.ofRun(context, null));
        if (bucket == null && key == null) {
            bucket = context.inputBucket();
            key = context.inputKey();
            if (bucket == null || key == null) {
                throw new IllegalStateException("This run was not started by a file; name the bucket and the file to read.");
            }
            context.log(String.format("Reading the file the run was started for: %s/%s.", bucket, key));
        }
        String format = FileConfigs.formatOf(Configs.text(config, "format", "auto"), key);
        byte[] content = this.buckets.read(context.tenantId(), bucket, key, Limits.MAX_FILE_BYTES);
        RowCollector rows = new RowCollector(key, Configs.integer(config, "maxRows", null));
        FileFormats.read(content, format, FileConfigs.options(config), rows);
        context.log(String.format("%d row(s) from %s/%s (%s, %,d bytes).", rows.size(), bucket, key, format, content.length));
        return StepResult.of(rows.toDataset());
    }
}
