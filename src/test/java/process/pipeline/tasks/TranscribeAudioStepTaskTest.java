package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.backing.Fakes;
import process.pipeline.backing.MediaFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * Transcribe audio: each row's recording, read from the run's workspace's own bucket, is sent to media's transcriber and
 * the row gains its transcript, language and length. The run's own files (an inbox) are a row each; another workspace's
 * file is never read; a busy transcriber is asked again after a growing wait; a refused file is the row's failure.
 */
class TranscribeAudioStepTaskTest {

    private static final long MINE = 2980L;
    private static final long THEIRS = 2981L;

    @TempDir
    Path dir;

    private final Fakes.TenantBuckets buckets = new Fakes.TenantBuckets();
    private final Fakes.Media media = new Fakes.Media();
    private final List<Duration> waits = new ArrayList<>();
    private final TranscribeAudioStepTask task = new TranscribeAudioStepTask(this.media, this.buckets, this.waits::add);

    private static byte[] audio(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private StreamTry run(Map<String, Object> config, Dataset input) {
        StreamTry t = new StreamTry(config, input, 8L << 20, this.dir, 2);
        t.tenant = MINE;
        t.runId = 91;
        return t;
    }

    @Test
    void theRunsOwnFilesAreARowEachAndGainTheirTranscript() throws Exception {
        this.buckets.put(MINE, "inbox/voice/call-1.m4a", audio("a1")).put(MINE, "inbox/voice/call-2.wav", audio("a2"));
        StreamTry t = this.run(config(), Dataset.EMPTY);
        t.inputBucket = "inbox";
        t.inputKeys = Arrays.asList("voice/call-1.m4a", "voice/call-2.wav");

        this.task.stream(t);

        assertThat(t.declared).containsExactly("bucket", "key", "file_name", "file_stem", "transcript", "language", "duration_seconds");
        assertThat(t.rows).hasSize(2);
        assertThat(t.rows.get(0)).containsEntry("bucket", "inbox").containsEntry("key", "voice/call-1.m4a")
            .containsEntry("file_name", "call-1.m4a").containsEntry("file_stem", "call-1").containsEntry("transcript", "text of call-1.m4a")
            .containsEntry("language", "en").containsEntry("duration_seconds", 75.0);
        // Each file once, billed under the run's step, attempt and row; the bytes are the bucket's.
        assertThat(this.media.calls).containsExactly("transcribe 2980 pipeline#91#step#a1#r1 call-1.m4a",
            "transcribe 2980 pipeline#91#step#a1#r2 call-2.wav");
        assertThat(new String(this.media.sent.get(1), StandardCharsets.UTF_8)).isEqualTo("a2");
        assertThat(t.scratchFiles()).as("the spooled recordings are removed").isZero();
        assertThat(String.join("\n", t.lines)).contains("2 recording(s) transcribed, 2.5 minute(s) of audio").doesNotContain("text of");
    }

    @Test
    void aKeyColumnNamesEachRowsFileAndTimestampsAddTheSegments() throws Exception {
        this.buckets.put(MINE, "lake/in/a.mp3", audio("x"));
        StreamTry t = this.run(config("bucket", "lake", "keyColumn", "path", "timestamps", true),
            Dataset.of(Arrays.asList(row("id", 7, "path", "in/a.mp3"))));

        this.task.stream(t);

        assertThat(t.declared).containsExactly("id", "path", "file_name", "file_stem", "transcript", "language", "duration_seconds", "segments");
        assertThat((String) t.rows.get(0).get("segments")).isEqualTo("[{\"startMs\":0,\"endMs\":1500,\"text\":\"text of a.mp3\"}]");
    }

    /**
     * MIG-426: a form's recording field (its uploads, as the submission holds them) names each row's file: read from where the
     * form stored it, named as it was uploaded.
     */
    @Test
    void aFormsRecordingFieldNamesTheRowsFile() throws Exception {
        this.buckets.put(MINE, "riverside-forms/intake/forms/form-9/uploads/u1-note.m4a", audio("x"));
        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("key", "intake/forms/form-9/uploads/u1-note.m4a");
        upload.put("bucket", "riverside-forms");
        upload.put("name", "voice note.m4a");
        StreamTry t = this.run(config("keyColumn", "recording"), Dataset.of(Arrays.asList(row("id", 7, "recording", Arrays.asList(upload)))));

        this.task.stream(t);

        assertThat(t.rows).hasSize(1);
        assertThat(t.rows.get(0)).containsEntry("transcript", "text of u1-note.m4a").containsEntry("file_name", "voice note.m4a")
            .containsEntry("file_stem", "voice note");
        assertThat(this.media.calls).containsExactly("transcribe 2980 pipeline#91#step#a1#r1 u1-note.m4a");
    }

    @Test
    void readS3sKeyColumnIsReadWithoutSayingSoAndTheRowsBucketIsUsed() throws Exception {
        this.buckets.put(MINE, "lake/in/b.ogg", audio("x"));
        StreamTry t = this.run(config(), Dataset.of(Arrays.asList(row("bucket", "lake", "key", "in/b.ogg", "size", 1))));

        this.task.stream(t);

        assertThat(t.rows.get(0)).containsEntry("transcript", "text of b.ogg");
    }

    /** The cross-workspace rule: the file is read as the run's workspace, so another workspace's object is not found. */
    @Test
    void anotherWorkspacesFileIsNeverReadNorSent() {
        this.buckets.put(THEIRS, "lake/voice/secret.wav", audio("theirs"));
        StreamTry t = this.run(config("bucket", "lake", "keyColumn", "key"), Dataset.of(Arrays.asList(row("key", "voice/secret.wav"))));

        assertThatThrownBy(() -> this.task.stream(t)).hasMessageContaining("No object at lake/voice/secret.wav in this workspace");
        assertThat(this.buckets.reads).containsExactly("2980:lake/voice/secret.wav");
        assertThat(this.media.calls).isEmpty();
    }

    @Test
    void aTypeTheTranscriberDoesNotReadIsRefusedBeforeAnythingIsRead() {
        StreamTry t = this.run(config("bucket", "lake"), Dataset.of(Arrays.asList(row("key", "in/notes.pdf"))));

        assertThatThrownBy(() -> this.task.stream(t)).hasMessageContaining("'notes.pdf' is not a recording the transcriber reads")
            .hasMessageContaining("mp3, m4a, wav, ogg, flac, webm");
        assertThat(this.buckets.reads).isEmpty();
    }

    @Test
    void moreFilesThanMaxFilesFailBeforeAnyIsRead() {
        StreamTry t = this.run(config("bucket", "lake", "maxFiles", 1),
            Dataset.of(Arrays.asList(row("key", "a.wav"), row("key", "b.wav"))));

        assertThatThrownBy(() -> this.task.stream(t)).hasMessageContaining("at most 1 (maxFiles)");
        assertThat(this.buckets.reads).isEmpty();
    }

    @Test
    void aBusyTranscriberIsAskedAgainAfterAGrowingWait() throws Exception {
        this.buckets.put(MINE, "lake/a.wav", audio("x"));
        this.media.busyFor = 3;
        StreamTry t = this.run(config("bucket", "lake"), Dataset.of(Arrays.asList(row("key", "a.wav"))));

        this.task.stream(t);

        assertThat(this.waits).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(14), Duration.ofSeconds(28));
        assertThat(t.rows.get(0)).containsEntry("transcript", "text of a.wav");
        assertThat(this.media.calls).hasSize(4).allSatisfy(c -> assertThat(c).contains("#r1 "));
    }

    @Test
    void aTranscriberThatStaysBusyFailsTheFile() {
        this.buckets.put(MINE, "lake/a.wav", audio("x"));
        this.media.busyFor = 100;
        StreamTry t = this.run(config("bucket", "lake"), Dataset.of(Arrays.asList(row("key", "a.wav"))));

        assertThatThrownBy(() -> this.task.stream(t)).hasMessageContaining("a.wav: The transcriber is busy.");
        assertThat(this.media.calls).hasSize(TranscribeAudioStepTask.BUSY_TRIES);
    }

    @Test
    void withNullARefusedFileIsKeptEmptyWithItsStatusAndWithSkipItIsDropped() throws Exception {
        this.buckets.put(MINE, "lake/quiet.wav", audio("x")).put(MINE, "lake/talk.wav", audio("y"));
        this.media.refusals.put("quiet.wav", new MediaFiles.Refused(422, "No speech was found in this recording.", 30));
        Dataset input = Dataset.of(Arrays.asList(row("key", "quiet.wav"), row("key", "talk.wav")));

        StreamTry kept = this.run(config("bucket", "lake", "onError", "null"), input);
        this.task.stream(kept);
        assertThat(kept.declared).endsWith("transcribe_status", "transcribe_error");
        assertThat(kept.rows.get(0)).containsEntry("transcript", null).containsEntry("transcribe_status", "failed")
            .containsEntry("transcribe_error", "No speech was found in this recording.");
        assertThat(kept.rows.get(1)).containsEntry("transcribe_status", "ok").containsEntry("transcribe_error", null);
        assertThat(this.media.calls).as("a refusal is not asked again").hasSize(2);

        StreamTry skipped = this.run(config("bucket", "lake", "onError", "skip"), input);
        this.task.stream(skipped);
        assertThat(skipped.rows).extracting(r -> r.get("key")).containsExactly("talk.wav");
        assertThat(String.join("\n", skipped.lines)).contains("WARN quiet.wav: No speech").contains("1 failed (skipped)");
    }

    /** A scheduled read that found nothing new hands an empty input on: no file, no failure. */
    @Test
    void anEmptyInputWithAKeyColumnIsNoFileNotAFailure() throws Exception {
        StreamTry t = this.run(config("bucket", "lake", "keyColumn", "key"), Dataset.EMPTY);

        this.task.stream(t);

        assertThat(t.rows).isEmpty();
        assertThat(t.declared).startsWith("key", "file_name");
        assertThat(this.media.calls).isEmpty();
    }

    @Test
    void noFileAndNoRunFilesSaysWhatToDo() {
        StreamTry t = this.run(config(), Dataset.EMPTY);
        assertThatThrownBy(() -> this.task.stream(t)).hasMessageContaining("name the column that holds each row's file key");
    }

    @Test
    void unavailableWhileMediaTakesNoPipelineFiles() {
        this.media.unavailable = "media-service takes no pipeline files yet";
        assertThat(this.task.unavailable()).hasValue("media-service takes no pipeline files yet");
    }

    @Test
    void theWaitGrowsAndIsCapped() {
        assertThat(TranscribeAudioStepTask.backoff(Duration.ZERO, 30)).isEqualTo(Duration.ofSeconds(30));
        assertThat(TranscribeAudioStepTask.backoff(Duration.ofSeconds(30), 10)).isEqualTo(Duration.ofSeconds(60));
        assertThat(TranscribeAudioStepTask.backoff(Duration.ofSeconds(200), 30)).isEqualTo(Duration.ofMinutes(5));
    }
}
