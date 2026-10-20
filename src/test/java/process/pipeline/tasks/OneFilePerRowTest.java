package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.RunOutput;
import process.pipeline.backing.Fakes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * "One file per row" for Save File and Upload to bucket: each row is a file of its own -- a JSON file is the row's object
 * -- named from the row's columns, which are made safe first (a value adds no folder and climbs out of none); a name taken
 * twice gets -2, and more rows than maxFiles fail before any file is written. One run over a folder, one file a voice.
 */
class OneFilePerRowTest {

    @TempDir
    Path dir;

    private final Fakes.TenantBuckets buckets = new Fakes.TenantBuckets();

    private StreamTry run(Map<String, Object> config, Dataset input) {
        StreamTry t = new StreamTry(config, input, 8L << 20, this.dir, 2);
        t.tenant = 2980L;
        t.runId = 93;
        return t;
    }

    private static Dataset answers() {
        return Dataset.of(Arrays.asList(
            row("file_stem", "call-1", "summary", "first", "keyPoints", "[\"a\",\"b\"]"),
            row("file_stem", "call-2", "summary", "second", "keyPoints", "[]"),
            row("file_stem", "call-1", "summary", "again", "keyPoints", "not json")));
    }

    @Test
    void saveFileKeepsARowAFileItsJsonColumnsAsListsAndATakenNameGetsANumber() throws Exception {
        StreamTry t = this.run(config("fileName", "{{file_stem}}-insight.json", "format", "json", "perRow", true,
            "jsonColumns", Arrays.asList("keyPoints")), answers());

        new SaveFileStepTask().stream(t);

        assertThat(t.files.keySet()).containsExactly("call-1-insight.json", "call-2-insight.json", "call-1-insight-2.json");
        String first = new String(t.files.get("call-1-insight.json"), StandardCharsets.UTF_8);
        assertThat(first.replaceAll("\\s+", "")).isEqualTo("{\"file_stem\":\"call-1\",\"summary\":\"first\",\"keyPoints\":[\"a\",\"b\"]}");
        assertThat(new String(t.files.get("call-1-insight-2.json"), StandardCharsets.UTF_8)).contains("\"keyPoints\" : \"not json\"");
        assertThat(t.recorded).extracting(RunOutput::getName).containsExactly("call-1-insight.json", "call-2-insight.json",
            "call-1-insight-2.json");
        assertThat(t.recorded).allSatisfy(o -> assertThat(o.getRows()).isEqualTo(1L));
    }

    @Test
    void uploadWritesARowAnObjectAndARowsValueAddsNoFolder() throws Exception {
        StreamTry t = this.run(config("bucket", "lake", "key", "insights/{{run}}/{{file_stem}}.jsonl", "format", "jsonl", "perRow", true),
            Dataset.of(Arrays.asList(row("file_stem", "../../secrets/x"), row("file_stem", "ok"))));

        new UploadBucketStepTask(this.buckets).stream(t);

        assertThat(this.buckets.uploads).containsExactly("2980:lake/insights/93/____secrets_x.jsonl application/x-ndjson; charset=utf-8",
            "2980:lake/insights/93/ok.jsonl application/x-ndjson; charset=utf-8");
        assertThat(t.recorded).extracting(RunOutput::getKey).containsExactly("insights/93/____secrets_x.jsonl", "insights/93/ok.jsonl");
    }

    @Test
    void moreRowsThanMaxFilesFailBeforeAnyFile() {
        StreamTry t = this.run(config("fileName", "{{file_stem}}.json", "format", "json", "perRow", true, "maxFiles", 2), answers());
        assertThatThrownBy(() -> new SaveFileStepTask().stream(t)).hasMessageContaining("at most 2 files (maxFiles)");
        assertThat(t.files).isEmpty();
    }

    @Test
    void oneFileForAllRowsStillTakesTheRunsPlaceholdersAndRefusesAnUnfilledOne() throws Exception {
        StreamTry t = this.run(config("fileName", "all-{{run}}.csv", "format", "csv"), answers());
        new SaveFileStepTask().stream(t);
        assertThat(t.files.keySet()).containsExactly("all-93.csv");

        StreamTry bad = this.run(config("fileName", "{{file_stem}}.csv", "format", "csv"), answers());
        assertThatThrownBy(() -> new SaveFileStepTask().stream(bad)).hasMessageContaining("names a placeholder this run has no value for");
    }

    @Test
    void aPlainNameIsCheckedAtSaveAndATemplateIsLeftToTheRun() {
        SaveFileStepTask task = new SaveFileStepTask();
        assertThat(task.check(config("fileName", "out.csv"))).isEmpty();
        assertThat(task.check(config("fileName", "{{file_stem}}.json", "perRow", true))).isEmpty();
    }

    @Test
    void aSegmentKeepsLettersDigitsDotsDashesUnderscoresAndSpacesOnly() {
        assertThat(FileRows.segment("call 1.m4a")).isEqualTo("call 1.m4a");
        assertThat(FileRows.segment("a/b\\c")).isEqualTo("a_b_c");
        assertThat(FileRows.segment("..")).isEqualTo("_");
        assertThat(FileRows.segment(".hidden")).isEqualTo("hidden");
        assertThat(FileRows.segment("é\u0000x")).isEqualTo("__x");
        assertThat(FileRows.segment(null)).isEqualTo("_");
        assertThat(FileRows.segment("x".repeat(500))).hasSize(FileRows.MAX_SEGMENT);
        Set<String> taken = new HashSet<>();
        assertThat(FileRows.unique("a/b.json", taken)).isEqualTo("a/b.json");
        assertThat(FileRows.unique("a/b.json", taken)).isEqualTo("a/b-2.json");
        assertThat(FileRows.unique("a.b/c", taken)).isEqualTo("a.b/c");
        assertThat(FileRows.unique("a.b/c", taken)).isEqualTo("a.b/c-2");
        Map<String, Object> rowValues = new LinkedHashMap<>();
        rowValues.put("run", 5);
        assertThat(FileRows.fillForRow("{{run}}/{{row}}-{{name}}", rowValues, row("name", "x/y", "run", "row's own"), 3))
            .isEqualTo("row_s own/3-x_y");
    }
}
