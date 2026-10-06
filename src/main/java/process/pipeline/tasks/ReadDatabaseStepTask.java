package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.DatabaseReader;
import process.pipeline.data.Limits;
import process.pipeline.data.RowCollector;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read Database (MIG-231): a query on one of the workspace's database connections (MIG-229), run by
 * integration-service -- which holds the password, allows only its read-only statements and runs them in a read-only,
 * time-bounded transaction. The step checks the same shape early so the form can say so: one statement, starting
 * with SELECT, WITH, VALUES or TABLE.
 */
@Component
public class ReadDatabaseStepTask extends RegisteredTask {

    static final int MAX_QUERY = 20_000;
    private static final Pattern READ_ONLY = Pattern.compile("^\\s*(select|with|values|table)\\b.*", Pattern.DOTALL);

    static final TaskSpec SPEC = TaskSpec.builder("read_database", "Read Database", TaskKind.READ)
        .description("Runs a read-only query on a workspace database connection (integration-service) and takes its rows.")
        .output(TaskSpec.rows("The query's rows, with its columns."))
        .config(JsonSchema.object()
            .required("connectionId", JsonSchema.integer().minimum(1).title("Connection").format("db-connection")
                .description("One of the workspace's database connections."))
            .required("query", JsonSchema.string().minLength(1).maxLength(MAX_QUERY).title("Query").format("sql")
                .description("One read-only statement: SELECT, WITH, VALUES or TABLE."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(Limits.MAX_ROWS).title("At most rows")
                .description("Stop after this many rows.")))
        .backing(TaskSpec.INTEGRATION)
        .retry(2, 30)
        .timeoutSeconds(300)
        .aiToolName("read_database")
        .build();

    private final DatabaseReader reader;

    public ReadDatabaseStepTask(DatabaseReader reader) {
        super(SPEC);
        this.reader = reader;
    }

    @Override
    public Optional<String> unavailable() {
        return this.reader.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        String query = Configs.text(config, "query", "");
        String body = query.trim().replaceAll(";\\s*$", "");
        if (!READ_ONLY.matcher(body.toLowerCase(Locale.ROOT)).matches()) {
            problems.add(new DefinitionProblem("query", "a read-only statement: SELECT, WITH, VALUES or TABLE"));
        } else if (body.contains(";")) {
            problems.add(new DefinitionProblem("query", "one statement only"));
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        Integer maxRows = Configs.integer(config, "maxRows", null);
        DatabaseReader.QueryCall call = new DatabaseReader.QueryCall();
        call.tenantId = context.tenantId();
        call.jobQueueId = context.jobQueueId();
        call.stepKey = context.stepKey();
        call.connectionId = Configs.longValue(config, "connectionId");
        call.query = Configs.text(config, "query", null);
        call.maxRows = maxRows == null ? Limits.MAX_ROWS + 1 : maxRows;
        DatabaseReader.QueryResult result = this.reader.query(call);
        RowCollector rows = new RowCollector("The query's answer", maxRows);
        for (Map<String, Object> row : result.rows) {
            if (rows.full()) {
                break;
            }
            rows.add(row);
        }
        if (result.truncated && maxRows == null) {
            throw new IllegalStateException(String.format("The query has more than %,d rows, the most a step holds; narrow it or set maxRows.",
                Limits.MAX_ROWS));
        }
        context.log(String.format("%d row(s) from connection %s.", rows.size(), config.get("connectionId")));
        return StepResult.of(result.columns == null ? rows.toDataset() : rows.toDataset(result.columns));
    }
}
