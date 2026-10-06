package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.DatabaseWriter;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Write Database (MIG-231): the step's input into a table of a workspace database connection -- insert, or upsert on
 * key columns -- in batches of {@value DatabaseWriter#BATCH}. <b>Off by default and unavailable</b>: MIG-229's
 * connections are read-only by design, and integration-service has no write path yet. Registered so the console and
 * the AI tool registry know it; it runs only once integration-service ships a write endpoint, this Core is switched
 * to it (process.pipeline.integration.database-write) and a workspace admin switches the task on.
 */
@Component
public class WriteDatabaseStepTask extends RegisteredTask {

    static final String IDENTIFIER = "^[A-Za-z_][A-Za-z0-9_]{0,62}(\\.[A-Za-z_][A-Za-z0-9_]{0,62})?$";

    static final TaskSpec SPEC = TaskSpec.builder("write_database", "Write Database", TaskKind.OUTPUT)
        .description("Inserts or upserts the rows into a table of a workspace database connection (integration-service). Off by default.")
        .input(TaskSpec.rows("The rows to write; their columns are the table's."))
        .output(TaskSpec.rows("The input, unchanged."))
        .config(JsonSchema.object()
            .required("connectionId", JsonSchema.integer().minimum(1).title("Connection").format("db-connection"))
            .required("table", JsonSchema.string().pattern(IDENTIFIER).title("Table")
                .description("table or schema.table: letters, digits and '_'"))
            .property("mode", JsonSchema.string().enumOf("insert", "upsert").title("Write as").defaultValue("insert"))
            .property("keyColumns", JsonSchema.array(JsonSchema.string().pattern(IDENTIFIER).format("column")).maxItems(10)
                .title("Key columns").description("upsert: the columns a row is matched on."))
            .property("columns", JsonSchema.array(JsonSchema.string().pattern(IDENTIFIER).format("column")).maxItems(200)
                .title("Columns").description("Only these columns; empty for all of the input's.")))
        .backing(TaskSpec.INTEGRATION)
        .timeoutSeconds(900)
        .requiredRole(TaskSpec.ROLE_ADMIN)
        .enabledByDefault(false)
        .aiToolName("write_database")
        .build();

    private final DatabaseWriter writer;

    public WriteDatabaseStepTask(DatabaseWriter writer) {
        super(SPEC);
        this.writer = writer;
    }

    @Override
    public Optional<String> unavailable() {
        return this.writer.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        if ("upsert".equals(config.get("mode")) && Configs.texts(config, "keyColumns").isEmpty()) {
            return Collections.singletonList(new DefinitionProblem("keyColumns", "an upsert needs the columns a row is matched on"));
        }
        return Collections.emptyList();
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        Dataset input = context.input();
        List<String> columns = Configs.texts(config, "columns");
        if (columns.isEmpty()) {
            columns = input.getColumns();
        }
        long written = 0;
        for (int from = 0; from < input.size(); from += DatabaseWriter.BATCH) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Write Database was stopped.");
            }
            List<Map<String, Object>> batch = new ArrayList<>();
            for (Map<String, Object> row : input.getRows().subList(from, Math.min(input.size(), from + DatabaseWriter.BATCH))) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (String column : columns) {
                    out.put(column, row.get(column));
                }
                batch.add(out);
            }
            DatabaseWriter.WriteCall call = new DatabaseWriter.WriteCall();
            call.tenantId = context.tenantId();
            call.jobQueueId = context.jobQueueId();
            call.stepKey = context.stepKey();
            call.connectionId = Configs.longValue(config, "connectionId");
            call.table = Configs.text(config, "table", null);
            call.mode = Configs.text(config, "mode", "insert");
            call.keyColumns = Configs.texts(config, "keyColumns");
            call.columns = columns;
            call.rows = batch;
            written += this.writer.write(call);
        }
        context.log(String.format("%d of %d row(s) written to %s.", written, input.size(), config.get("table")));
        return StepResult.nothing(written);
    }
}
