package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.backing.Fakes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Read CSV/JSON/Parquet reads one object of a workspace bucket as rows. */
class ReadFileStepTaskTest {

    private final Fakes.Buckets buckets = new Fakes.Buckets();
    private final ReadFileStepTask task = new ReadFileStepTask(this.buckets);

    private Dataset read(String key, byte[] content, Object... more) throws Exception {
        this.buckets.objects.put("lake/" + key, content);
        Object[] settings = new Object[4 + more.length];
        settings[0] = "bucket";
        settings[1] = "lake";
        settings[2] = "key";
        settings[3] = key;
        System.arraycopy(more, 0, settings, 4, more.length);
        return this.task.run(TaskContext.of(config(settings), Collections.emptyList())).getOutput();
    }

    @Test
    void aCsvsCellsAreTextAndItsHeaderNamesTheColumns() throws Exception {
        Dataset out = this.read("claims.csv", "id,name,amount\n007,\"Acme, Inc\",12.50\n".getBytes(StandardCharsets.UTF_8));
        assertThat(out.getColumns()).containsExactly("id", "name", "amount");
        assertThat(out.getRows()).containsExactly(row("id", "007", "name", "Acme, Inc", "amount", "12.50"));
        Dataset headless = this.read("raw.txt", "a;b\n".getBytes(StandardCharsets.UTF_8), "format", "csv", "header", false, "delimiter", ";");
        assertThat(headless.getRows()).containsExactly(row("c1", "a", "c2", "b"));
    }

    @Test
    void jsonRowsAtAPathAndJsonLines() throws Exception {
        assertThat(this.read("x.json", "{\"data\":[{\"id\":1},{\"id\":2}]}".getBytes(StandardCharsets.UTF_8), "rowsPath", "data").getRows())
            .containsExactly(row("id", 1L), row("id", 2L));
        assertThat(this.read("x.jsonl", "{\"id\":1}\n\n{\"id\":2,\"ok\":true}\n".getBytes(StandardCharsets.UTF_8)).getRows())
            .containsExactly(row("id", 1L), row("id", 2L, "ok", true));
        assertThatThrownBy(() -> this.read("bad.jsonl", "{\"id\":1}\nnot json\n".getBytes(StandardCharsets.UTF_8)))
            .hasMessage("Line 2 is not JSON.");
        assertThatThrownBy(() -> this.read("notes.txt", new byte[0])).hasMessageContaining("set the format");
    }

    /** MIG-239: a run an inbox arrival started reads its own file when the step names none. */
    @Test
    void withNoFileNamedItReadsTheFileTheRunWasStartedFor() throws Exception {
        this.buckets.objects.put("inbox/intake/2026/claims.csv", "id\n1\n".getBytes(StandardCharsets.UTF_8));
        TaskContext context = TaskContext.of(config(), Collections.emptyList());
        context.inputBucket = "inbox";
        context.inputKey = "intake/2026/claims.csv";
        assertThat(this.task.run(context).getOutput().getRows()).containsExactly(row("id", "1"));
        assertThat(context.lines).contains("INFO Reading the file the run was started for: inbox/intake/2026/claims.csv.");
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config(), Collections.emptyList())))
            .hasMessage("This run was not started by a file; name the bucket and the file to read.");
        assertThat(this.task.check(config("bucket", "lake"))).containsExactly(new DefinitionProblem("key",
            "name both the bucket and the file, or neither (the file the run was started for)"));
    }

    /** MIG-360: a run that took a batch of waiting inbox files reads them all, in order, each row with its file. */
    @Test
    void aBatchRunReadsEveryFileItWasStartedFor() throws Exception {
        this.buckets.objects.put("inbox/intake/a.csv", "id\n1\n2\n".getBytes(StandardCharsets.UTF_8));
        this.buckets.objects.put("inbox/intake/b.csv", "id,extra\n3,x\n".getBytes(StandardCharsets.UTF_8));
        TaskContext context = TaskContext.of(config(), Collections.emptyList());
        context.inputBucket = "inbox";
        context.inputKey = "intake/a.csv";
        context.inputKeys = Arrays.asList("intake/a.csv", "intake/b.csv");
        Dataset out = this.task.run(context).getOutput();
        assertThat(out.getRows()).containsExactly(row("id", "1", "_source_key", "intake/a.csv"), row("id", "2", "_source_key", "intake/a.csv"),
            row("id", "3", "extra", "x", "_source_key", "intake/b.csv"));
        assertThat(context.lines).contains("INFO Reading the 2 files the run was started for, in the order they arrived.");
        Dataset capped = this.task.run(withKeys(config("maxRows", 2))).getOutput();
        assertThat(capped.getRows()).hasSize(2);
    }

    private static TaskContext withKeys(Map<String, Object> config) {
        TaskContext context = TaskContext.of(config, Collections.emptyList());
        context.inputBucket = "inbox";
        context.inputKey = "intake/a.csv";
        context.inputKeys = Arrays.asList("intake/a.csv", "intake/b.csv");
        return context;
    }

    @Test
    void parquetIsReadThroughDuckDb() throws Exception {
        Path file = Files.createTempFile("mig231-", ".parquet");
        Files.delete(file);
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:"); Statement statement = duck.createStatement()) {
            statement.execute("COPY (SELECT * FROM (VALUES (1, 'Acme', 12.5, true), (2, 'Beta', NULL, false)) t(id, name, amount, active)) TO '"
                + file.toAbsolutePath() + "' (FORMAT PARQUET)");
        }
        try {
            Dataset out = this.read("claims.parquet", Files.readAllBytes(file));
            assertThat(out.getColumns()).containsExactly("id", "name", "amount", "active");
            assertThat(out.getRows()).containsExactly(row("id", 1L, "name", "Acme", "amount", 12.5, "active", true),
                row("id", 2L, "name", "Beta", "amount", null, "active", false));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
