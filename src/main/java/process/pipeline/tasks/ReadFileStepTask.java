package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.data.RowCollector;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
 *
 * MIG-344: when the engine streams it, a CSV or JSON Lines file is read as a stream, row by row, straight into the
 * step's output file -- no file-size, row or cell limit beyond {@code Limits.MAX_STREAM_*}, and storage-service bills
 * the bytes taken. JSON and Parquet are still read whole, within the in-memory bounds.
 */
@Component
public class ReadFileStepTask extends RegisteredTask implements StreamingStepTask {

    static final TaskSpec SPEC = TaskSpec.builder("read_file", "Read CSV/JSON/Parquet", TaskKind.READ)
        .description("Reads one file of a workspace bucket — CSV, JSON, JSON Lines or Parquet — as rows (storage-service).")
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
    public StepResult stream(StreamContext context) throws Exception {
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
        Integer maxRows = Configs.integer(config, "maxRows", null);
        if (context.inMemory() || !FileFormats.streams(format)) {
            // Read whole, as before MIG-344: held to the in-memory bounds.
            byte[] content = this.buckets.read(context.tenantId(), bucket, key, Limits.MAX_FILE_BYTES);
            RowCollector rows = new RowCollector(key, maxRows);
            FileFormats.read(content, format, FileConfigs.options(config), rows);
            context.log(String.format("%d row(s) from %s/%s (%s, %,d bytes).", rows.size(), bucket, key, format, content.length));
            Dataset dataset = rows.toDataset();
            context.output().declare(dataset.getColumns());
            for (Map<String, Object> row : dataset.getRows()) {
                context.output().add(row);
            }
            return StepResult.streamed(dataset.size());
        }
        long[] bytes = {0};
        try (InputStream in = new FilterInputStream(this.buckets.open(context.tenantId(), bucket, key, context.maxFileBytes())) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                bytes[0] += b < 0 ? 0 : 1;
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                bytes[0] += Math.max(0, n);
                return n;
            }
        }) {
            FileFormats.readStream(in, format, FileConfigs.options(config), context.output(), maxRows, context.maxRows(), key);
        }
        context.log(String.format("%d row(s) from %s/%s (%s, %,d bytes, streamed).", context.output().size(), bucket, key, format, bytes[0]));
        return StepResult.streamed(context.output().size());
    }
}
