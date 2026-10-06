package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.backing.Fakes;
import process.pipeline.data.Limits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Read Database runs a read-only query through integration-service and takes its rows. */
class ReadDatabaseStepTaskTest {

    private final Fakes.Database database = new Fakes.Database();
    private final ReadDatabaseStepTask task = new ReadDatabaseStepTask(this.database);

    @Test
    void theQuerysRowsInItsColumnOrder() throws Exception {
        this.database.rows = new ArrayList<>(Arrays.asList(row("id", 1L, "name", "Acme"), row("id", 2L, "name", "Beta")));
        Dataset out = this.task.run(TaskContext.of(config("connectionId", 5, "query", "select id, name from customers"),
            Collections.emptyList())).getOutput();
        assertThat(out.getColumns()).containsExactly("id", "name");
        assertThat(out.getRows()).hasSize(2);
        assertThat(this.database.queries.get(0).connectionId).isEqualTo(5L);
        assertThat(this.database.queries.get(0).tenantId).isEqualTo(TaskContext.TENANT);
        assertThat(this.database.queries.get(0).maxRows).as("one more than a step holds, to know there were more").isEqualTo(Limits.MAX_ROWS + 1);
    }

    @Test
    void moreRowsThanAStepHoldsFailsUnlessMaxRowsAskedForFewer() throws Exception {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.add(row("n", (long) i));
        }
        this.database.rows = many;
        assertThat(this.task.run(TaskContext.of(config("connectionId", 5, "query", "select n from t", "maxRows", 3), Collections.emptyList()))
            .getOutput().size()).isEqualTo(3);
    }

    @Test
    void onlyOneReadOnlyStatement() {
        assertThat(this.task.check(config("query", "WITH x AS (SELECT 1) SELECT * FROM x;"))).isEmpty();
        assertThat(this.task.check(config("query", "delete from customers"))).containsExactly(
            new DefinitionProblem("query", "a read-only statement: SELECT, WITH, VALUES or TABLE"));
        assertThat(this.task.check(config("query", "select 1; drop table customers"))).containsExactly(
            new DefinitionProblem("query", "one statement only"));
    }

    @Test
    void truncatedWithoutMaxRowsFails() {
        Fakes.Database capped = new Fakes.Database() {
            @Override
            public QueryResult query(QueryCall call) {
                QueryResult result = super.query(call);
                result.truncated = true;
                return result;
            }
        };
        capped.rows = new ArrayList<>(Collections.singletonList(row("n", 1L)));
        assertThatThrownBy(() -> new ReadDatabaseStepTask(capped).run(TaskContext.of(config("connectionId", 5, "query", "select 1"),
            Collections.emptyList()))).hasMessageContaining("more than 50,000 rows");
    }
}
