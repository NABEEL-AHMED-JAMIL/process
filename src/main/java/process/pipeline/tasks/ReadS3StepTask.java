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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Read S3 (MIG-231): the objects under a prefix of one of the workspace's buckets, through storage-service. As
 * {@code list} (the default), one row per object -- key and size; as a file format, every object's rows one after
 * another, each with its {@code _source_key}. At most {@code limit} objects ({@value Limits#MAX_OBJECTS}), and the
 * whole within the step's bounds.
 */
@Component
public class ReadS3StepTask extends RegisteredTask {

    static final TaskSpec SPEC = TaskSpec.builder("read_s3", "Read S3", TaskKind.READ)
        .description("Lists the objects under a prefix of a workspace bucket, or reads all of them as rows (storage-service).")
        .output(TaskSpec.rows("list: key, size per object. A file format: each object's rows, with _source_key."))
        .config(FileConfigs.parsing(FileConfigs.bucket(JsonSchema.object())
            .property("prefix", JsonSchema.string().maxLength(1024).title("Prefix").defaultValue("")
                .description("Only objects whose keys start with this; {{date}} and the other run placeholders are filled in."))
            .property("format", JsonSchema.string().enumOf("list", "auto", "csv", "json", "jsonl", "parquet").title("Read as")
                .defaultValue("list").description("list: one row per object. Otherwise each object's rows (auto: by its extension)."))
            .property("limit", JsonSchema.integer().minimum(1).maximum(Limits.MAX_OBJECTS).title("At most objects").defaultValue(100))))
        .backing(TaskSpec.STORAGE)
        .retry(3, 10)
        .timeoutSeconds(600)
        .aiToolName("read_s3")
        .build();

    private final BucketStore buckets;

    public ReadS3StepTask(BucketStore buckets) {
        super(SPEC);
        this.buckets = buckets;
    }

    @Override
    public Optional<String> unavailable() {
        return this.buckets.unavailable();
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        String bucket = Configs.text(config, "bucket", null);
        String prefix = Templates.fill(Configs.text(config, "prefix", ""), Templates.ofRun(context, null));
        String format = Configs.text(config, "format", "list");
        int limit = Configs.integer(config, "limit", 100);
        BucketStore.Listing listing = this.buckets.list(context.tenantId(), bucket, prefix, limit);
        if (listing.truncated) {
            context.warn(String.format("More than %d objects under '%s'; only the first %d are read.", limit, prefix, limit));
        }
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
