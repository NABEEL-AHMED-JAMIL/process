package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.DefinitionProblem;
import process.pipeline.Definitions;
import process.pipeline.StepResult;
import process.pipeline.backing.Fakes;
import process.pipeline.backing.PipelineNotifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: the output tasks -- Save File, Upload to bucket, Write Database, Send Notification -- each pass their rows on. */
class OutputStepTasksTest {

    private final List<Map<String, Object>> rows = Arrays.asList(row("id", 1L, "name", "Acme, Inc", "note", null),
        row("id", 2L, "name", "Beta", "note", "x"));

    @Test
    void saveFileKeepsTheRowsAsAFileWithTheRunAndPassesThemOn() throws Exception {
        TaskContext context = TaskContext.of(config("fileName", "claims.csv"), this.rows);
        StepResult result = new SaveFileStepTask().run(context);
        assertThat(result.getOutput()).as("the next step reads what this one read").isNull();
        assertThat(result.getRecordsOut()).isEqualTo(2L);
        assertThat(new String(context.files.get("claims.csv"), StandardCharsets.UTF_8))
            .isEqualTo("id,name,note\r\n1,\"Acme, Inc\",\r\n2,Beta,x\r\n");
        // Wave 4: in the run's manifest, as the file it kept.
        assertThat(context.recorded).extracting(o -> o.getKind() + ":" + o.getName() + ":" + o.getFormat() + ":" + o.getRows() + ":"
            + o.getBytes() + ":" + o.getBucket()).containsExactly("file:claims.csv:csv:2:" + context.files.get("claims.csv").length + ":null");
        TaskContext json = TaskContext.of(config("fileName", "claims.jsonl", "format", "jsonl"), this.rows);
        new SaveFileStepTask().run(json);
        assertThat(new String(json.files.get("claims.jsonl"), StandardCharsets.UTF_8))
            .isEqualTo("{\"id\":1,\"name\":\"Acme, Inc\",\"note\":null}\n{\"id\":2,\"name\":\"Beta\",\"note\":\"x\"}\n");
    }

    @Test
    void uploadWritesTheRowsToTheWorkspacesBucketUnderTheFilledKey() throws Exception {
        Fakes.Buckets buckets = new Fakes.Buckets();
        UploadBucketStepTask task = new UploadBucketStepTask(buckets);
        TaskContext uploaded = TaskContext.of(config("bucket", "exports", "key", "claims/{{pipeline}}-{{run}}.json", "format", "json"),
            this.rows);
        StepResult result = task.run(uploaded);
        // Wave 4: in the run's manifest with its bucket alias and key, which storage's browse endpoints download.
        assertThat(uploaded.recorded).extracting(o -> o.getKind() + ":" + o.getName() + ":" + o.getFormat() + ":" + o.getRows() + ":"
            + o.getBucket() + ":" + o.getKey()).containsExactly("bucket:CLAIMS-88001.json:json:2:exports:claims/CLAIMS-88001.json");
        assertThat(result.getRecordsOut()).isEqualTo(2L);
        assertThat(buckets.uploads).containsExactly("exports/claims/CLAIMS-88001.json application/json");
        assertThat(buckets.lastTenant).isEqualTo(TaskContext.TENANT);
        assertThat(new String(buckets.objects.get("exports/claims/CLAIMS-88001.json"), StandardCharsets.UTF_8)).startsWith("[{\"id\":1,");
        assertThatThrownBy(() -> task.run(TaskContext.of(config("bucket", "exports", "key", "../{{nope}}.csv"), this.rows)))
            .hasMessageContaining("is not an object key a step writes");
        assertThat(task.spec().requiredRole()).isEqualTo("TENANT_ADMIN");
    }

    @Test
    void writeDatabaseIsOffByDefaultAndWritesInBatchesWhenOn() throws Exception {
        Fakes.Database database = new Fakes.Database();
        WriteDatabaseStepTask task = new WriteDatabaseStepTask(database);
        assertThat(task.spec().enabledByDefault()).isFalse();
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(row("id", (long) i, "name", "n" + i, "extra", "dropped"));
        }
        StepResult result = task.run(TaskContext.of(config("connectionId", 3, "table", "public.claims", "mode", "upsert",
            "keyColumns", Collections.singletonList("id"), "columns", Arrays.asList("id", "name")), many));
        assertThat(result.getRecordsOut()).isEqualTo(501L);
        assertThat(database.writes).extracting(call -> call.rows.size()).containsExactly(500, 1);
        assertThat(database.writes.get(0).rows.get(0)).containsOnlyKeys("id", "name");
        assertThat(database.writes.get(0).keyColumns).containsExactly("id");
        assertThat(task.check(config("mode", "upsert"))).containsExactly(
            new DefinitionProblem("keyColumns", "an upsert needs the columns a row is matched on"));
    }

    @Test
    void sendNotificationFillsItsTextAndSendsOnlyWhenAsked() throws Exception {
        Fakes.Notifier notifier = new Fakes.Notifier();
        SendNotificationStepTask task = new SendNotificationStepTask(notifier);
        StepResult result = task.run(TaskContext.of(config("title", "{{rows}} claims in {{pipeline}}", "message", "Run {{run}}.",
            "severity", "WARNING"), this.rows));
        assertThat(result.getRecordsOut()).isEqualTo(2L);
        PipelineNotifier.Notice notice = notifier.sent.get(0);
        assertThat(notice.title).isEqualTo("2 claims in CLAIMS");
        assertThat(notice.body).isEqualTo("Run 88001.");
        assertThat(notice.to).isEqualTo("owner");
        assertThat(notice.ownerUserId).isEqualTo(7L);
        assertThat(notice.tenantId).isEqualTo(TaskContext.TENANT);
        assertThat(notice.link).isEqualTo("/jobList");
        task.run(TaskContext.of(config("title", "none", "when", "no_rows"), this.rows));
        assertThat(notifier.sent).hasSize(1);
        assertThat(task.check(config("title", "x", "to", "users"))).containsExactly(new DefinitionProblem("userIds", "name at least one person"));
    }

    @Test
    void sendNotificationTellsTheFirstRowsColumnsButNeverOverTheRunsPlaceholders() throws Exception {
        Fakes.Notifier notifier = new Fakes.Notifier();
        SendNotificationStepTask task = new SendNotificationStepTask(notifier);
        task.run(TaskContext.of(config("title", "Weekly digest, run {{run}}", "message", "{{digest}} ({{rows}} row)"),
            Collections.singletonList(Definitions.row("digest", "Nausea leads.", "run", "not this"))));
        assertThat(notifier.sent.get(0).title).isEqualTo("Weekly digest, run 88001");
        assertThat(notifier.sent.get(0).body).isEqualTo("Nausea leads. (1 row)");
    }

    @Test
    void aRunStartedForAFileNamesItAsInputKeyAndInputName() throws Exception {
        Fakes.Notifier notifier = new Fakes.Notifier();
        SendNotificationStepTask task = new SendNotificationStepTask(notifier);
        TaskContext context = TaskContext.of(config("title", "{{input_name}}", "message", "{{input_key}}"), this.rows);
        context.inputKey = "intake/2026-10-06/77-scan-001.jpeg";
        task.run(context);
        assertThat(notifier.sent.get(0).title).isEqualTo("77-scan-001.jpeg");
        assertThat(notifier.sent.get(0).body).isEqualTo("intake/2026-10-06/77-scan-001.jpeg");
        task.run(TaskContext.of(config("title", "{{input_name}}"), this.rows));
        assertThat(notifier.sent.get(1).title).as("no file: left as written").isEqualTo("{{input_name}}");
    }

    /** MIG-360: a run that took a batch of waiting inbox files says how many, and which. */
    @Test
    void aBatchRunNamesItsFilesAsInputCountAndInputKeys() throws Exception {
        Fakes.Notifier notifier = new Fakes.Notifier();
        SendNotificationStepTask task = new SendNotificationStepTask(notifier);
        TaskContext context = TaskContext.of(config("title", "{{input_count}} file(s)", "message", "{{input_keys}}"), this.rows);
        context.inputKey = "intake/a.jpeg";
        context.inputKeys = Arrays.asList("intake/a.jpeg", "intake/b.jpeg");
        task.run(context);
        assertThat(notifier.sent.get(0).title).isEqualTo("2 file(s)");
        assertThat(notifier.sent.get(0).body).isEqualTo("intake/a.jpeg,intake/b.jpeg");
        TaskContext one = TaskContext.of(config("title", "{{input_count}} file(s)"), this.rows);
        one.inputKey = "intake/a.jpeg";
        task.run(one);
        assertThat(notifier.sent.get(1).title).isEqualTo("1 file(s)");
    }
}
