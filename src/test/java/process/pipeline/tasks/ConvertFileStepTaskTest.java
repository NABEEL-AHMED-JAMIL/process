package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.RunOutput;
import process.pipeline.backing.Fakes;
import process.pipeline.backing.MediaFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * Convert file: each row's file, read from the run's workspace's own bucket, converted by media's converter and written to
 * a bucket at a key made from the row (its values cannot add folders) or kept with the run; the row says where, its pages
 * and its status.
 */
class ConvertFileStepTaskTest {

    private static final long MINE = 2980L;

    @TempDir
    Path dir;

    private final Fakes.TenantBuckets buckets = new Fakes.TenantBuckets();
    private final Fakes.Media media = new Fakes.Media();
    private final ConvertFileStepTask task = new ConvertFileStepTask(this.media, this.buckets, d -> { });

    private StreamTry run(Map<String, Object> config, Dataset input) {
        StreamTry t = new StreamTry(config, input, 8L << 20, this.dir, 2);
        t.tenant = MINE;
        t.runId = 92;
        return t;
    }

    @Test
    void eachFileIsConvertedAndWrittenAtTheDefaultKeyInItsOwnBucket() throws Exception {
        this.buckets.put(MINE, "docs/in/visit notes.docx", new byte[] {1, 2});
        StreamTry t = this.run(config("bucket", "docs"), Dataset.of(Arrays.asList(row("key", "in/visit notes.docx"))));

        this.task.stream(t);

        assertThat(t.declared).containsExactly("key", "file_name", "file_stem", "converted_key", "converted_pages", "converted_bytes",
            "convert_status");
        assertThat(t.rows.get(0)).containsEntry("converted_key", "converted/visit notes.pdf").containsEntry("converted_pages", 3)
            .containsEntry("convert_status", "ok");
        assertThat(this.buckets.uploads).containsExactly("2980:docs/converted/visit notes.pdf application/pdf");
        assertThat(this.media.calls).containsExactly("convert 2980 pipeline#92#step#a1#r1 visit notes.docx");
        assertThat(t.recorded).hasSize(1);
        RunOutput out = t.recorded.get(0);
        assertThat(out.getKind()).isEqualTo(RunOutput.BUCKET);
        assertThat(out.getPages()).isEqualTo(3);
        assertThat(out.getSha256()).hasSize(64);
    }

    @Test
    void aRowsValuesCannotAddFoldersOrClimbOutOfOne() throws Exception {
        this.buckets.put(MINE, "docs/a.docx", new byte[] {1});
        StreamTry t = this.run(config("bucket", "docs", "outputKey", "out/{{patient}}/{{file_stem}}.{{format}}", "outputBucket", "exports"),
            Dataset.of(Arrays.asList(row("key", "a.docx", "patient", "../../other/x"))));

        this.task.stream(t);

        assertThat(this.buckets.uploads).containsExactly("2980:exports/out/____other_x/a.pdf application/pdf");
    }

    @Test
    void keptWithTheRunTheResultIsAnOutputFileNamedByTheKeysLastPart() throws Exception {
        this.buckets.put(MINE, "docs/a.xlsx", new byte[] {1});
        this.buckets.put(MINE, "docs/b/a.xlsx", new byte[] {2});
        StreamTry t = this.run(config("bucket", "docs", "writeTo", "run", "targetFormat", "csv"),
            Dataset.of(Arrays.asList(row("key", "a.xlsx"), row("key", "b/a.xlsx"))));

        this.task.stream(t);

        assertThat(t.files.keySet()).containsExactly("a.csv", "a-2.csv");
        assertThat(new String(t.files.get("a.csv"), StandardCharsets.UTF_8)).startsWith("%PDF-converted a.xlsx");
        assertThat(t.rows).extracting(r -> r.get("converted_key")).containsExactly("a.csv", "a-2.csv");
        assertThat(t.rows.get(0).get("converted_pages")).isNull();
        assertThat(this.buckets.uploads).isEmpty();
        assertThat(t.recorded).extracting(RunOutput::getKind).containsOnly(RunOutput.FILE);
    }

    @Test
    void aPairTheConverterRefusesIsTheRowsFailure() throws Exception {
        this.buckets.put(MINE, "docs/a.mp3", new byte[] {1});
        this.media.refusals.put("a.mp3", new MediaFiles.Refused(400, "'mp3' isn't a supported input format.", 30));
        StreamTry t = this.run(config("bucket", "docs", "onError", "null"), Dataset.of(Arrays.asList(row("key", "a.mp3"))));

        this.task.stream(t);

        assertThat(t.rows.get(0)).containsEntry("convert_status", "failed").containsEntry("converted_key", null)
            .containsEntry("convert_error", "'mp3' isn't a supported input format.");
        assertThat(this.buckets.uploads).isEmpty();
    }

    @Test
    void aKeyThatLeavesThePlaceholderUnfilledIsRefused() {
        this.buckets.put(MINE, "docs/a.docx", new byte[] {1});
        StreamTry t = this.run(config("bucket", "docs", "outputKey", "out/{{nothing}}.pdf"), Dataset.of(Arrays.asList(row("key", "a.docx"))));
        assertThatThrownBy(() -> this.task.stream(t)).hasMessageContaining("is not an object key a step writes");
        assertThat(this.media.calls).isEmpty();
    }

    @Test
    void theSavedKeyMayNotStartWithASlashOrClimb() {
        assertThat(this.task.check(config("outputKey", "/x/{{file_stem}}.pdf"))).hasSize(1);
        assertThat(this.task.check(config("outputKey", "x/../{{file_stem}}.pdf"))).hasSize(1);
        assertThat(this.task.check(config("outputKey", "x/{{file_stem}}.pdf"))).isEmpty();
    }
}
