package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.backing.Fakes;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Read API takes a saved request's answer as rows, through integration-service's runner. */
class ReadApiStepTaskTest {

    private final Fakes.Api api = new Fakes.Api();
    private final ReadApiStepTask task = new ReadApiStepTask(this.api);

    @Test
    void theRowsAtThePathBecomeRowsNestedValuesAsJsonText() throws Exception {
        this.api.answer = variables -> "{\"data\":{\"items\":[{\"id\":1,\"name\":\"Acme\",\"tags\":[\"x\"]},{\"id\":2,\"name\":\"Beta\"}]}}";
        TaskContext context = TaskContext.of(config("requestId", 12, "environmentId", 3, "variables", config("since", "{{date}}", "run", "{{run}}"),
            "rowsPath", "data.items"), Collections.emptyList());

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("id", "name", "tags");
        assertThat(out.getRows()).containsExactly(row("id", 1L, "name", "Acme", "tags", "[\"x\"]"), row("id", 2L, "name", "Beta"));
        assertThat(this.api.calls).hasSize(1);
        assertThat(this.api.calls.get(0).tenantId).as("the run's workspace, never the config's").isEqualTo(TaskContext.TENANT);
        assertThat(this.api.calls.get(0).requestId).isEqualTo(12L);
        assertThat(this.api.calls.get(0).environmentId).isEqualTo(3L);
        assertThat(this.api.calls.get(0).variables).containsEntry("run", "88001").doesNotContainValue("{{date}}");
    }

    @Test
    void anObjectAnswerIsOneRowAndMaxRowsStopsEarly() throws Exception {
        this.api.answer = variables -> "{\"total\":3}";
        assertThat(this.task.run(TaskContext.of(config("requestId", 1), Collections.emptyList())).getOutput().getRows())
            .containsExactly(row("total", 3L));
        this.api.answer = variables -> "[{\"n\":1},{\"n\":2},{\"n\":3}]";
        assertThat(this.task.run(TaskContext.of(config("requestId", 1, "maxRows", 2), Collections.emptyList())).getOutput().size()).isEqualTo(2);
    }

    @Test
    void aFailedRunFailsTheStepInTheRunnersWordsAndUnavailableIsTheRunners() {
        this.api.outcome = "FAILED";
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("requestId", 1), Collections.emptyList())))
            .hasMessage("The API request failed (HTTP 500): upstream said no.");
        this.api.unavailable = "not deployed";
        assertThat(this.task.unavailable()).contains("not deployed");
    }

    @Test
    void aFanOutRowsPathAndFieldsFlattenANestedAnswer() throws Exception {
        // A FHIR searchset Bundle, as any nested JSON answer: the rows are entry[].resource, the columns dot paths into them.
        this.api.answer = variables -> "{\"resourceType\":\"Bundle\",\"entry\":["
            + "{\"resource\":{\"id\":\"o1\",\"subject\":{\"reference\":\"Patient/p1\"},\"code\":{\"coding\":[{\"code\":\"4548-4\"}]},"
            + "\"valueQuantity\":{\"value\":7.1,\"unit\":\"%\"}}},"
            + "{\"resource\":{\"id\":\"o2\",\"subject\":{\"reference\":\"Patient/p2\"},\"code\":{\"coding\":[{\"code\":\"39156-5\"}]}}},"
            + "{\"search\":{\"mode\":\"include\"}}]}";
        TaskContext context = TaskContext.of(config("requestId", 5, "rowsPath", "entry[].resource", "fields", java.util.Arrays.asList(
            config("path", "id", "target", "observation_id"), config("path", "subject.reference", "target", "patient_ref"),
            config("path", "code.coding[0].code", "target", "loinc"), config("path", "valueQuantity.value", "target", "value"),
            config("path", "valueQuantity", "target", "quantity"))), Collections.emptyList());

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("observation_id", "patient_ref", "loinc", "value", "quantity");
        assertThat(out.getRows()).hasSize(2);
        assertThat(out.getRows().get(0)).containsEntry("patient_ref", "Patient/p1").containsEntry("loinc", "4548-4")
            .containsEntry("value", 7.1).containsEntry("quantity", "{\"value\":7.1,\"unit\":\"%\"}");
        assertThat(out.getRows().get(1)).containsEntry("observation_id", "o2").containsEntry("value", null);
    }

    @Test
    void aFanOutPathOverNothingIsNoRowsAndAPlainPathStillPicksOneElement() throws Exception {
        this.api.answer = variables -> "{\"resourceType\":\"Bundle\",\"total\":0}";
        assertThat(this.task.run(TaskContext.of(config("requestId", 5, "rowsPath", "entry[*].resource"), Collections.emptyList()))
            .getOutput().size()).isEqualTo(0);
        this.api.answer = variables -> "{\"results\":[{\"a\":{\"b\":1}},{\"a\":{\"b\":2}}]}";
        assertThat(this.task.run(TaskContext.of(config("requestId", 5, "rowsPath", "results[1].a"), Collections.emptyList()))
            .getOutput().getRows()).containsExactly(row("b", 2L));
        assertThat(process.pipeline.data.Values.all(process.pipeline.data.Values.JSON.readTree("{\"x\":[[1,2],[3]]}"), "x[][]"))
            .hasSize(3);
    }

    @Test
    void oneFanOutFieldMakesARowPerValueWithTheOtherColumnsRepeated() throws Exception {
        this.api.answer = variables -> "{\"results\":[{\"id\":\"r1\",\"drug\":[{\"name\":\"A\"}],\"reaction\":[{\"pt\":\"Nausea\"},{\"pt\":\"Rash\"}]},"
            + "{\"id\":\"r2\",\"drug\":[{\"name\":\"B\"}],\"reaction\":[]}]}";
        Dataset out = this.task.run(TaskContext.of(config("requestId", 5, "rowsPath", "results", "fields", java.util.Arrays.asList(
            config("path", "id", "target", "report"), config("path", "drug[0].name", "target", "drug"),
            config("path", "reaction[].pt", "target", "reaction"))), Collections.emptyList())).getOutput();
        assertThat(out.getRows()).containsExactly(row("report", "r1", "drug", "A", "reaction", "Nausea"),
            row("report", "r1", "drug", "A", "reaction", "Rash"), row("report", "r2", "drug", "B", "reaction", null));
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("requestId", 5, "rowsPath", "results", "fields", java.util.Arrays.asList(
            config("path", "drug[].name", "target", "d"), config("path", "reaction[].pt", "target", "r"))), Collections.emptyList())))
            .hasMessageContaining("must fan out over the same list");
        assertThat(this.task.check(config("requestId", 5, "fields", java.util.Arrays.asList(config("path", "a[].b[].c", "target", "x")))))
            .extracting(p -> p.toString()).anyMatch(t -> t.contains("fan out once"));
    }

    @Test
    void variablesTakeTheFirstInputRowsColumnsUnderTheRunsPlaceholders() throws Exception {
        this.api.answer = variables -> "[]";
        this.task.run(TaskContext.of(config("requestId", 9, "variables", config("condition", "{{condition}}", "city", "{{city}}",
            "run", "{{run}}")), java.util.Arrays.asList(row("condition", "asthma", "city", "Chicago", "run", "not this"),
            row("condition", "second row", "city", "x"))));
        assertThat(this.api.calls.get(0).variables).containsEntry("condition", "asthma").containsEntry("city", "Chicago")
            .containsEntry("run", "88001");
    }
}
