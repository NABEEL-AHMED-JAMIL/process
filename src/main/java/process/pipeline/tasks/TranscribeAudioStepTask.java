package process.pipeline.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.BucketStore;
import process.pipeline.backing.MediaFiles;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Transcribe audio: each row's recording -- a file in the run's workspace's own bucket (FileRows: the row's key, or the
 * files the run was started for) -- turned into text by the platform's transcriber, through media-service, which bills the
 * minutes (ai.transcript.minutes) to the run's workspace. The row gains transcript, language, duration_seconds and, with
 * timestamps, segments (JSON: startMs, endMs, text); with onError skip or null also transcribe_status and transcribe_error.
 *
 * <ul>
 *   <li>Types: {@link #TYPES}, checked by the key's extension before anything is read; at most {@value #MAX_AUDIO_MB} MB a
 *       recording (and the step's own file bound) and four hours long (the transcriber's limit, its 422).</li>
 *   <li>A busy transcriber (503, or media unreachable) is asked again after the wait it names, growing each time, up to
 *       {@value #BUSY_TRIES} tries a file; a refusal of the file itself (silent, no speech, too long) is the row's failure,
 *       which onError handles: fail the step, skip the row, or keep it with the transcript empty.</li>
 *   <li>At most {@code maxFiles} rows (default 20); an input with more fails before any file is read.</li>
 *   <li>Each file is billed once per attempt (the key is the run's step, attempt and row), however often the step is
 *       tried. The recording is spooled to the try's scratch directory, never held in memory, and removed after.</li>
 *   <li>The step's log says how many files and minutes, never what was said.</li>
 * </ul>
 */
@Component
public class TranscribeAudioStepTask extends RegisteredTask implements StreamingStepTask {

    static final List<String> TYPES = Arrays.asList("mp3", "m4a", "wav", "ogg", "flac", "webm");
    static final int MAX_AUDIO_MB = 500;
    static final int BUSY_TRIES = 6;
    /** The longest wait between two tries of a busy transcriber. */
    static final Duration MAX_WAIT = Duration.ofMinutes(5);
    static final int MAX_FILES = Limits.MAX_OBJECTS;

    static final String TRANSCRIPT = "transcript";
    static final String LANGUAGE = "language";
    static final String DURATION = "duration_seconds";
    static final String SEGMENTS = "segments";
    static final String STATUS = "transcribe_status";
    static final String ERROR = "transcribe_error";

    static final TaskSpec SPEC = TaskSpec.builder("transcribe_audio", "Transcribe audio", TaskKind.PROCESS)
        .description("Turns each row's recording (mp3, m4a, wav, ogg, flac or webm in a workspace bucket) into text with the "
            + "platform's transcriber, and adds the transcript, its language and length as columns.")
        .input(TaskSpec.rows("Rows naming a recording each (a key column, Read S3's list), or none: the files the run was started for."))
        .output(TaskSpec.rows("The input's columns with file_name and file_stem, then transcript, language, duration_seconds and, "
            + "with timestamps, segments."))
        .config(FileRows.addressing(JsonSchema.object(), "recordings")
            .property("timestamps", JsonSchema.bool().title("Timestamps").defaultValue(false)
                .description("Also add segments: each stretch of speech with its start and end (JSON)."))
            .property("onError", JsonSchema.string().enumOf("fail", "skip", "null").title("When a file fails").defaultValue("fail")
                .description("fail the step, skip the row, or keep it with the transcript empty (and transcribe_status and "
                    + "transcribe_error say why)."))
            .property(AtOnce.SETTING, AtOnce.setting("Files transcribed"))
            .property("maxFiles", JsonSchema.integer().minimum(1).maximum(MAX_FILES).title("At most files").defaultValue(20)
                .description("An input with more rows fails before any file is read.")))
        .backing(TaskSpec.MEDIA)
        .timeoutSeconds(7200)
        .aiToolName("transcribe_audio")
        .items(ItemRole.EACH).build();

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MediaFiles media;
    private final BucketStore buckets;
    private final Sleeper sleeper;

    /** How the step waits for a busy transcriber (a test's is instant). */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    @Autowired
    public TranscribeAudioStepTask(MediaFiles media, BucketStore buckets) {
        this(media, buckets, d -> Thread.sleep(d.toMillis()));
    }

    TranscribeAudioStepTask(MediaFiles media, BucketStore buckets, Sleeper sleeper) {
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
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        boolean timestamps = Configs.bool(config, "timestamps", false);
        String onError = Configs.text(config, "onError", "fail");
        int maxFiles = Configs.integer(config, "maxFiles", 20);
        boolean tracked = !"fail".equals(onError);
        long maxBytes = Math.min((long) MAX_AUDIO_MB * 1024 * 1024, context.maxFileBytes());

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
                    throw new IllegalStateException(String.format("The input names %,d files; this step transcribes at most %d (maxFiles).",
                        input.size(), maxFiles));
                }
                for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                    for (Map<String, Object> row : batch) {
                        targets.add(FileRows.of(context, config, keyColumn, row));
                    }
                }
            }
        }
        FileRows.warnSeveral(context, Configs.text(config, "keyColumn", FileRows.KEY), targets);
        if (targets.size() > maxFiles) {
            throw new IllegalStateException(String.format("The run names %,d files; this step transcribes at most %d (maxFiles).",
                targets.size(), maxFiles));
        }
        columns.addAll(Arrays.asList(FileRows.FILE_NAME, FileRows.FILE_STEM, TRANSCRIPT, LANGUAGE, DURATION));
        if (timestamps) {
            columns.add(SEGMENTS);
        }
        if (tracked) {
            columns.add(STATUS);
            columns.add(ERROR);
        }
        RowSink out = context.output();
        out.declare(new ArrayList<>(columns));

        int done = 0;
        int failed = 0;
        double seconds = 0;
        Path scratch = context.scratch();
        int atOnce = AtOnce.of(config);
        if (atOnce > 1) {
            context.log(String.format("Calls at once: %d (the workspace's calls together at most %d).", atOnce, AtOnce.workspaceCap()));
        }
        // MIG-418: the files, Calls at once at a time (the transcriber's own busy answers are waited out per file, as before);
        // the rows go out in the files' order. A failure stops new files only when the step fails on it.
        // MIG-420: each file's item key, on the step's thread, and the time each file took.
        String[] tags = new String[targets.size()];
        for (int i = 0; i < tags.length; i++) {
            tags[i] = context.rowTag(targets.get(i).row, i);
        }
        long[] took = new long[targets.size()];
        List<AtOnce.Outcome<MediaFiles.Transcript>> outcomes = AtOnce.run(context.tenantId(), targets.size(), atOnce, !tracked, i -> {
            long began = System.nanoTime();
            try {
                return this.transcribe(context, targets.get(i), i + 1, tags[i], timestamps, maxBytes, scratch);
            } finally {
                took[i] = (System.nanoTime() - began) / 1_000_000L;
            }
        });
        for (int i = 0; i < targets.size(); i++) {
            AtOnce.Outcome<MediaFiles.Transcript> outcome = outcomes.get(i);
            if (!outcome.made) {
                break;
            }
            FileRows.Target target = targets.get(i);
            Map<String, Object> row = new LinkedHashMap<>(target.row);
            // MIG-420: the recording's item, on the step's thread, in the files' order.
            context.itemTook(target.row, took[i]);
            if (outcome.error == null) {
                MediaFiles.Transcript t = outcome.value;
                row.put(TRANSCRIPT, t.text);
                row.put(LANGUAGE, t.language);
                row.put(DURATION, t.durationSeconds);
                if (timestamps) {
                    row.put(SEGMENTS, t.segments == null ? null : JSON.writeValueAsString(t.segments));
                }
                if (tracked) {
                    row.put(STATUS, "ok");
                    row.put(ERROR, null);
                }
                done++;
                seconds += t.durationSeconds == null ? 0 : t.durationSeconds;
            } else {
                Exception fileFailed = outcome.error;
                if (fileFailed instanceof InterruptedException) {
                    throw fileFailed;
                }
                String why = String.format("%s: %s", target.key.isEmpty() ? "Row " + (i + 1) : target.key, fileFailed.getMessage());
                context.itemFailed(target.row, fileFailed.getMessage());
                if (!tracked) {
                    throw new IllegalStateException(why, fileFailed);
                }
                failed++;
                context.warn(why);
                if ("skip".equals(onError)) {
                    continue;
                }
                row.put(TRANSCRIPT, null);
                row.put(LANGUAGE, null);
                row.put(DURATION, null);
                if (timestamps) {
                    row.put(SEGMENTS, null);
                }
                row.put(STATUS, "failed");
                row.put(ERROR, fileFailed.getMessage());
            }
            out.add(row);
        }
        context.log(String.format("%d recording(s) transcribed, %.1f minute(s) of audio%s; %d row(s) out.", done, seconds / 60.0,
            failed == 0 ? "" : String.format(", %d failed (%s)", failed, "skip".equals(onError) ? "skipped" : "kept empty"), out.size()));
        return StepResult.streamed(out.size());
    }

    /** One file: checked, spooled from the bucket, sent, and asked again while the transcriber is busy. */
    private MediaFiles.Transcript transcribe(StreamContext context, FileRows.Target target, int number, String tag, boolean timestamps, long maxBytes,
        Path scratch) throws Exception {
        if (target.key.isEmpty()) {
            throw new IllegalArgumentException(target.missing());
        }
        if (!TYPES.contains(target.extension())) {
            throw new IllegalArgumentException(String.format("'%s' is not a recording the transcriber reads (%s).", target.fileName(),
                String.join(", ", TYPES)));
        }
        if (target.bucket == null || target.bucket.isEmpty()) {
            throw new IllegalArgumentException("Name the bucket the recording is in.");
        }
        Path spool = scratch.resolve("audio-" + number + "." + target.extension());
        try {
            try (InputStream in = this.buckets.open(context.tenantId(), target.bucket, target.key, maxBytes)) {
                Files.copy(in, spool, StandardCopyOption.REPLACE_EXISTING);
            }
            // MIG-420: billed once per file per attempt, keyed by the file's item in an item run (its row number otherwise).
            String usageKey = String.format("pipeline#%d#%s#a%d#r%s", context.jobQueueId(), context.stepKey(), context.attempt(), tag);
            Duration wait = Duration.ZERO;
            for (int attempt = 1; ; attempt++) {
                try {
                    return this.media.transcribe(context.tenantId(), usageKey, spool, target.fileName(), timestamps);
                } catch (IOException busy) {
                    boolean retryable = !(busy instanceof MediaFiles.Refused) || ((MediaFiles.Refused) busy).busy();
                    if (!retryable || attempt >= BUSY_TRIES) {
                        throw busy;
                    }
                    int named = busy instanceof MediaFiles.Refused ? ((MediaFiles.Refused) busy).retryAfterSeconds() : 15;
                    wait = backoff(wait, named);
                    context.log(String.format("The transcriber is busy (%s); %s waits %d s and asks again (try %d of %d).",
                        busy.getMessage(), target.fileName(), wait.getSeconds(), attempt + 1, BUSY_TRIES));
                    this.sleeper.sleep(wait);
                }
            }
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    /** The next wait: what the transcriber named, at least twice the last wait, at most {@link #MAX_WAIT}. */
    static Duration backoff(Duration last, int namedSeconds) {
        long next = Math.max(Math.max(1, namedSeconds), last.getSeconds() * 2);
        return Duration.ofSeconds(Math.min(next, MAX_WAIT.getSeconds()));
    }
}
