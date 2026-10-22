package process.pipeline.tasks;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.RunOutput;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.Streams;
import process.pipeline.backing.BucketStore;
import process.pipeline.backing.MediaFiles;
import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Convert file: each row's file -- in the run's workspace's own bucket, named as transcribe_audio names its (FileRows) --
 * converted by the platform's document converter (Tools > Converter, through media-service, which bills convert.documents
 * to the run's workspace): a document, spreadsheet, presentation or drawing to PDF, a text document to txt, docx or md, a
 * spreadsheet to csv or xlsx... -- the pairs the converter makes; another pair is the row's failure, in its words.
 *
 * <p>The result is written to a workspace bucket at {@code outputKey} (the row's columns and {@code {{format}}} fill it,
 * each made safe), or kept with the run as one of its output files. The row gains converted_key (the object key, or the
 * run file's name), converted_pages (a PDF's pages), converted_bytes and convert_status; with onError skip or null also
 * convert_error. At most {@code maxFiles} rows; each file is billed once per attempt.</p>
 */
@Component
public class ConvertFileStepTask extends RegisteredTask implements StreamingStepTask {

    /** The targets offered; media decides which of them a file's family converts to. */
    static final List<String> TARGETS = Arrays.asList("pdf", "docx", "odt", "rtf", "txt", "md", "html", "xlsx", "ods", "csv", "pptx",
        "odp", "png", "jpg");
    static final int MAX_FILES = Limits.MAX_OBJECTS;
    static final int BUSY_TRIES = 6;
    /** The converter's own largest input (a CSV to PDF); smaller pairs are refused by media at their own limit. */
    static final long MAX_INPUT_BYTES = 500L * 1024 * 1024;
    static final String DEFAULT_KEY = "converted/{{file_stem}}.{{format}}";

    static final String KEY = "converted_key";
    static final String PAGES = "converted_pages";
    static final String BYTES = "converted_bytes";
    static final String STATUS = "convert_status";
    static final String ERROR = "convert_error";

    static final TaskSpec SPEC = TaskSpec.builder("convert_file", "Convert file", TaskKind.PROCESS)
        .description("Converts each row's file in a workspace bucket with the platform's converter (to PDF, text, docx, csv...) "
            + "and writes the result to a bucket or keeps it with the run.")
        .input(TaskSpec.rows("Rows naming a file each (a key column, Read S3's list), or none: the files the run was started for."))
        .output(TaskSpec.rows("The input's columns with file_name and file_stem, then converted_key, converted_pages, converted_bytes "
            + "and convert_status."))
        .config(FileRows.addressing(JsonSchema.object(), "files")
            .property("targetFormat", JsonSchema.string().enumOf(TARGETS.toArray(new String[0])).title("Convert to").defaultValue("pdf")
                .description("The converter's pairs: a document to pdf, txt, docx, odt, rtf, md or html; a spreadsheet to pdf, csv, xlsx "
                    + "or ods; a presentation to pdf or pptx; images to png or jpg."))
            .property("writeTo", JsonSchema.string().enumOf("bucket", "run").title("Write the result to").defaultValue("bucket")
                .description("bucket: an object in a workspace bucket at the key below. run: a file kept with the run, in its outputs."))
            .property("outputBucket", JsonSchema.string().maxLength(255).format("bucket").title("Output bucket")
                .description("Where to write it; empty: the file's own bucket."))
            .property("outputKey", JsonSchema.string().minLength(1).maxLength(1024).format("template").title("Output key")
                .defaultValue(DEFAULT_KEY).description("The row's columns ({{file_stem}}), {{format}}, {{row}} and the run's "
                    + "placeholders fill it; a row's values cannot add folders. Kept with the run: its last part is the file's name."))
            .property("onError", JsonSchema.string().enumOf("fail", "skip", "null").title("When a file fails").defaultValue("fail")
                .description("fail the step, skip the row, or keep it with no result (and convert_error says why)."))
            .property("maxFiles", JsonSchema.integer().minimum(1).maximum(MAX_FILES).title("At most files").defaultValue(20)
                .description("An input with more rows fails before any file is read.")))
        .backing(TaskSpec.MEDIA)
        .timeoutSeconds(3600)
        .aiToolName("convert_file")
        .items(ItemRole.EACH).build();

    private final MediaFiles media;
    private final BucketStore buckets;
    private final TranscribeAudioStepTask.Sleeper sleeper;

    @Autowired
    public ConvertFileStepTask(MediaFiles media, BucketStore buckets) {
        this(media, buckets, d -> Thread.sleep(d.toMillis()));
    }

    ConvertFileStepTask(MediaFiles media, BucketStore buckets, TranscribeAudioStepTask.Sleeper sleeper) {
        super(SPEC);
        this.media = media;
        this.buckets = buckets;
        this.sleeper = sleeper;
    }

    @Override
    public Optional<String> unavailable() {
        Optional<String> files = this.media.unavailable();
        return files.isPresent() ? files : this.buckets.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        String key = Configs.text(config, "outputKey", DEFAULT_KEY);
        if (key.startsWith("/") || key.contains("..")) {
            problems.add(new DefinitionProblem("outputKey", "no leading '/' and no '..'"));
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        String format = Configs.text(config, "targetFormat", "pdf");
        boolean toRun = "run".equals(Configs.text(config, "writeTo", "bucket"));
        String outputBucket = Configs.text(config, "outputBucket", null);
        String keyTemplate = Configs.text(config, "outputKey", DEFAULT_KEY);
        String onError = Configs.text(config, "onError", "fail");
        int maxFiles = Configs.integer(config, "maxFiles", 20);
        boolean tracked = !"fail".equals(onError);
        long maxBytes = Math.min(MAX_INPUT_BYTES, context.maxFileBytes());

        List<FileRows.Target> targets = new ArrayList<>();
        Set<String> columns = new LinkedHashSet<>();
        try (RowSource input = context.openInput()) {
            // A read that found nothing new (a scheduled run since the last one) is no file to take, not a mistake.
            boolean nothing = input.size() == 0 && Configs.text(config, "keyColumn", null) != null && context.inputKeys().isEmpty();
            String keyColumn = nothing ? null : FileRows.keyColumn(config, input.columns());
            if (nothing) {
                columns.addAll(input.columns());
                if (!columns.contains(Configs.text(config, "keyColumn", ""))) {
                    columns.add(Configs.text(config, "keyColumn", ""));
                }
                context.log("No file to take: the input has no rows.");
            } else if (keyColumn == null) {
                targets.addAll(FileRows.runFiles(context, config));
                columns.addAll(Arrays.asList(FileRows.BUCKET, FileRows.KEY));
            } else {
                columns.addAll(input.columns());
                if (input.size() > maxFiles) {
                    throw new IllegalStateException(String.format("The input names %,d files; this step converts at most %d (maxFiles).",
                        input.size(), maxFiles));
                }
                for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                    for (Map<String, Object> row : batch) {
                        targets.add(FileRows.of(context, config, keyColumn, row));
                    }
                }
            }
        }
        if (targets.size() > maxFiles) {
            throw new IllegalStateException(String.format("The run names %,d files; this step converts at most %d (maxFiles).",
                targets.size(), maxFiles));
        }
        columns.addAll(Arrays.asList(FileRows.FILE_NAME, FileRows.FILE_STEM, KEY, PAGES, BYTES, STATUS));
        if (tracked) {
            columns.add(ERROR);
        }
        RowSink out = context.output();
        out.declare(new ArrayList<>(columns));

        Map<String, Object> runValues = Templates.ofRun(context, targets.size());
        runValues.put("format", format);
        Set<String> written = new HashSet<>();
        Path scratch = context.scratch();
        int done = 0;
        int failed = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Convert file was stopped.");
            }
            FileRows.Target target = targets.get(i);
            Map<String, Object> row = new LinkedHashMap<>(target.row);
            long began = System.nanoTime();
            try {
                String name = FileRows.fillForRow(keyTemplate, runValues, target.row, i + 1);
                String where = toRun ? FileRows.unique(FileRows.plainName(FileRows.nameOf(name)), written)
                    : FileRows.unique(FileRows.checkedKey(name), written);
                Path converted = scratch.resolve("converted-" + (i + 1) + "." + format);
                MediaFiles.Converted made = this.convert(context, target, i + 1, context.rowTag(target.row, i), format, maxBytes, scratch, converted);
                try {
                    String sha = this.write(context, toRun, outputBucket == null ? target.bucket : outputBucket, where, format, converted);
                    RunOutput output = toRun ? RunOutput.file(where, format, 1, made.bytes)
                        : RunOutput.bucket(outputBucket == null ? target.bucket : outputBucket, where, format, 1, made.bytes);
                    output = output.withSha256(sha);
                    if (made.pages != null) {
                        output = output.withPages(made.pages);
                    }
                    // MIG-420: the converted file is this row's item's.
                    context.recordOutput(output, target.row);
                } finally {
                    Files.deleteIfExists(converted);
                }
                row.put(KEY, where);
                row.put(PAGES, made.pages);
                row.put(BYTES, made.bytes);
                row.put(STATUS, "ok");
                if (tracked) {
                    row.put(ERROR, null);
                }
                done++;
                context.itemTook(target.row, (System.nanoTime() - began) / 1_000_000L);
            } catch (InterruptedException stopped) {
                throw stopped;
            } catch (Exception fileFailed) {
                String why = String.format("%s: %s", target.key.isEmpty() ? "Row " + (i + 1) : target.key, fileFailed.getMessage());
                context.itemFailed(target.row, fileFailed.getMessage());
                context.itemTook(target.row, (System.nanoTime() - began) / 1_000_000L);
                if (!tracked) {
                    throw new IllegalStateException(why, fileFailed);
                }
                failed++;
                context.warn(why);
                if ("skip".equals(onError)) {
                    continue;
                }
                row.put(KEY, null);
                row.put(PAGES, null);
                row.put(BYTES, null);
                row.put(STATUS, "failed");
                row.put(ERROR, fileFailed.getMessage());
            }
            out.add(row);
        }
        context.log(String.format("%d file(s) converted to %s%s%s; %d row(s) out.", done, format, toRun ? ", kept with the run" : "",
            failed == 0 ? "" : String.format(", %d failed (%s)", failed, "skip".equals(onError) ? "skipped" : "kept with no result"), out.size()));
        return StepResult.streamed(out.size());
    }

    private MediaFiles.Converted convert(StreamContext context, FileRows.Target target, int number, String tag, String format, long maxBytes, Path scratch,
        Path converted) throws Exception {
        if (target.key.isEmpty()) {
            throw new IllegalArgumentException("The row names no file.");
        }
        if (target.extension().isEmpty()) {
            throw new IllegalArgumentException(String.format("'%s' has no extension, so its format is not known.", target.fileName()));
        }
        if (target.bucket == null || target.bucket.isEmpty()) {
            throw new IllegalArgumentException("Name the bucket the file is in.");
        }
        Path spool = scratch.resolve("source-" + number + "." + target.extension());
        try {
            try (InputStream in = this.buckets.open(context.tenantId(), target.bucket, target.key, maxBytes)) {
                Files.copy(in, spool, StandardCopyOption.REPLACE_EXISTING);
            }
            String usageKey = String.format("pipeline#%d#%s#a%d#r%s", context.jobQueueId(), context.stepKey(), context.attempt(), tag);
            Duration wait = Duration.ZERO;
            for (int attempt = 1; ; attempt++) {
                try {
                    return this.media.convert(context.tenantId(), usageKey, spool, target.fileName(), format, converted);
                } catch (IOException busy) {
                    boolean retryable = !(busy instanceof MediaFiles.Refused) || ((MediaFiles.Refused) busy).busy();
                    if (!retryable || attempt >= BUSY_TRIES) {
                        throw busy;
                    }
                    int named = busy instanceof MediaFiles.Refused ? ((MediaFiles.Refused) busy).retryAfterSeconds() : 15;
                    wait = TranscribeAudioStepTask.backoff(wait, named);
                    context.log(String.format("The converter is busy (%s); %s waits %d s and asks again (try %d of %d).",
                        busy.getMessage(), target.fileName(), wait.getSeconds(), attempt + 1, BUSY_TRIES));
                    this.sleeper.sleep(wait);
                }
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** The converted file to the bucket or the run; answers its sha256. */
    private String write(StreamContext context, boolean toRun, String bucket, String where, String format, Path converted) throws Exception {
        if (toRun) {
            StreamContext.KeptFile kept = context.keepFile(where, new ArrayList<>(), 1, out -> Files.copy(converted, out));
            return kept.sha256;
        }
        MessageDigest digest = Streams.sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(converted), digest)) {
            this.buckets.upload(context.tenantId(), bucket, where, in, Files.size(converted), contentType(format));
        }
        return Streams.hex(digest.digest());
    }

    static String contentType(String format) {
        return FileFormats.contentType(format);
    }
}
