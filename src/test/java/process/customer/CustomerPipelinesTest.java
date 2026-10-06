package process.customer;

import com.fasterxml.jackson.databind.JsonNode;
import org.barco.platform.api.Problem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.pipeline.PipelineDefinition;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-332: POST /v1/pipelines/{id}/runs checks before it starts -- the scope, the key, the request's shape, the record
 * against the input contract (422 with each field's path, nothing started), the files -- and an idempotent replay starts
 * nothing more; the list pages by cursor.
 */
class CustomerPipelinesTest {

    static final long TENANT = 2946L;

    private final PipelineCatalogue catalogue = mock(PipelineCatalogue.class);
    private final InputContracts contracts = mock(InputContracts.class);
    private final RunFiles files = mock(RunFiles.class);
    private final RunIntake intake = mock(RunIntake.class);
    private final MemoryReceipts receipts = new MemoryReceipts();
    private final CustomerPipelines pipelines = new CustomerPipelines(this.catalogue, this.contracts, this.files, this.intake,
        new Idempotency(this.receipts));

    @BeforeEach
    void asAClient() {
        this.client("runs:write", "pipelines:read");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void client(String... scopes) {
        TenantContext.set(TENANT, TenantContext.API_CLIENT, null, "client:cl_test");
        TenantContext.setApiClient("cl_test", new LinkedHashSet<>(Arrays.asList(scopes)));
    }

    private static PipelineCatalogue.Entry pipeline(long jobId, PipelineDefinition.ContractRef contract) {
        PipelineDefinition definition = new PipelineDefinition();
        PipelineDefinition.Settings settings = new PipelineDefinition.Settings();
        settings.setInputContract(contract);
        definition.setSettings(settings);
        return new PipelineCatalogue.Entry(jobId, TENANT, "Orders", "Active", "Completed", "ORDERS", definition);
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static InputContracts.Checked verdict(boolean valid, Problem.FieldError... errors) {
        InputContracts.Checked checked = new InputContracts.Checked();
        checked.valid = valid;
        checked.errors = Arrays.asList(errors);
        return checked;
    }

    private void started() {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", "7001");
        run.put("status", "queued");
        when(this.intake.start(any(), any())).thenReturn(new RunIntake.Outcome(run, null));
    }

    @Test
    void aRecordTheContractRefusesIs422WithEachPathAndNothingStarts() {
        PipelineDefinition.ContractRef orders = PipelineDefinition.ContractRef.of(null, "orders", 2);
        when(this.catalogue.find(TENANT, "42")).thenReturn(Optional.of(pipeline(42L, orders)));
        when(this.contracts.check(eq(TENANT), eq(orders), any(JsonNode.class), eq("record"))).thenReturn(verdict(false,
            new Problem.FieldError("record.amount", "must be a number"), new Problem.FieldError("record.customer", "is required")));

        CustomerAnswer answer = this.pipelines.startRun("42", json("{\"record\":{\"amount\":\"lots\"}}"), "order-0001");

        assertThat(answer.status).isEqualTo(422);
        assertThat(answer.body.get("type")).isEqualTo("/problems/validation");
        assertThat(answer.body.toString()).contains("path=record.amount").contains("path=record.customer").doesNotContain("lots");
        verify(this.intake, never()).start(any(), any());
    }

    @Test
    void aPipelineWithAContractNeedsARecord() {
        PipelineDefinition.ContractRef orders = PipelineDefinition.ContractRef.of(9L, null, null);
        when(this.catalogue.find(TENANT, "42")).thenReturn(Optional.of(pipeline(42L, orders)));
        when(this.contracts.check(eq(TENANT), eq(orders), any(JsonNode.class), eq("record"))).thenReturn(verdict(true));

        CustomerAnswer answer = this.pipelines.startRun("42", json("{}"), "order-0002");

        assertThat(answer.status).isEqualTo(422);
        assertThat(answer.body.toString()).contains("path=record");
    }

    @Test
    void aGoodStartIs202WithItsLocationAndAReplayStartsNothingMore() {
        when(this.catalogue.find(TENANT, "42")).thenReturn(Optional.of(pipeline(42L, null)));
        this.started();

        CustomerAnswer first = this.pipelines.startRun("42", json("{\"record\":{\"a\":1},\"reference\":\"po-1\"}"), "order-0003");
        CustomerAnswer again = this.pipelines.startRun("42", json("{\"record\":{\"a\":1},\"reference\":\"po-1\"}"), "order-0003");
        CustomerAnswer other = this.pipelines.startRun("42", json("{\"record\":{\"a\":2}}"), "order-0003");

        assertThat(first.status).isEqualTo(202);
        assertThat(first.location).isEqualTo("/v1/runs/7001");
        assertThat(again.replayed).isTrue();
        assertThat(again.status).isEqualTo(202);
        assertThat(again.location).isEqualTo("/v1/runs/7001");
        assertThat(again.body).isEqualTo(first.body);
        assertThat(other.status).isEqualTo(409);
        assertThat(other.body.get("type")).isEqualTo("/problems/idempotency-conflict");
        verify(this.intake, times(1)).start(any(), any());
    }

    @Test
    void theFilesMustBeTheWorkspacesOwn() {
        when(this.catalogue.find(TENANT, "42")).thenReturn(Optional.of(pipeline(42L, null)));
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        found.put("01JOURS0000000000000000000", Collections.<String, Object>singletonMap("id", "01JOURS0000000000000000000"));
        when(this.files.of(eq(TENANT), anyCollection())).thenReturn(found);

        CustomerAnswer answer = this.pipelines.startRun("42", json("{\"files\":[\"01JOURS0000000000000000000\",\"01JTHEIRS000000000000000000\",7]}"),
            "order-0004");

        assertThat(answer.status).isEqualTo(422);
        assertThat(answer.body.toString()).contains("path=files[1]").contains("path=files[2]").doesNotContain("path=files[0]");
        verify(this.intake, never()).start(any(), any());
    }

    @Test
    void anUnreadableOrMisshapenRequestIsRefusedAndStoresNothingButItsAnswer() {
        when(this.catalogue.find(TENANT, "42")).thenReturn(Optional.of(pipeline(42L, null)));

        assertThat(this.pipelines.startRun("42", json("{not json"), "order-0005").status).isEqualTo(400);
        CustomerAnswer shape = this.pipelines.startRun("42", json("{\"record\":[1],\"files\":\"x\",\"reference\":5}"), "order-0006");
        assertThat(shape.status).isEqualTo(422);
        assertThat(shape.body.toString()).contains("path=record").contains("path=files").contains("path=reference");
        assertThat(this.pipelines.startRun("42", json("{}"), "short").status).as("a key the rule refuses").isEqualTo(400);
        assertThat(this.pipelines.startRun("42", json("{}"), null).status).isEqualTo(400);
        assertThat(this.pipelines.startRun("nope", json("{}"), "order-0007").status).isEqualTo(404);
        verify(this.intake, never()).start(any(), any());
    }

    @Test
    void everyCallChecksItsScope() {
        this.client("events:write");
        assertThat(this.pipelines.startRun("42", json("{}"), "order-0008").status).isEqualTo(403);
        assertThat(this.pipelines.list(null, null).status).isEqualTo(403);
        assertThat(this.pipelines.get("42").status).isEqualTo(403);
        assertThat(this.receipts.rows).as("a refused scope keeps no key").isEmpty();
    }

    @Test
    void theListPagesByAnOpaqueCursor() {
        List<PipelineCatalogue.Entry> rows = new ArrayList<>();
        for (long id = 30; id > 27; id--) {
            rows.add(pipeline(id, null));
        }
        when(this.catalogue.page(eq(TENANT), eq(null), eq(3))).thenReturn(rows);

        CustomerAnswer page = this.pipelines.list(2, null);

        assertThat(page.status).isEqualTo(200);
        assertThat((List<?>) page.body.get("data")).hasSize(2);
        assertThat(page.body.get("nextCursor")).isNotNull();
        assertThat(page.body.toString()).contains("acceptsFiles=true").contains("emits=[run.started, run.completed, run.failed]");
        assertThat(this.pipelines.list(500, null).status).isEqualTo(400);
        assertThat(this.pipelines.list(null, "nonsense!").status).isEqualTo(400);
        verify(this.contracts, never()).check(anyLong(), any(), any(), anyString());
    }
}
