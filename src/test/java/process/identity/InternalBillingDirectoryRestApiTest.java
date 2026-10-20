package process.identity;

import process.pipeline.PipelineDefinitionStore;
import org.junit.jupiter.api.Test;
import process.model.enums.Status;
import process.model.repository.AppUserRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.barco.platform.tenancy.RowSecurity;
import org.barco.platform.tenancy.RowSecurityCaller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What billing-service asks Core (MIG-88/89): the facts the nightly measurement counts (seats, topics in use),
 * with the shared service token, and nothing else admits it. Workspaces and names come from identity-service.
 */
class InternalBillingDirectoryRestApiTest {

    private static final String TOKEN = "t0ken";

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SourceTaskTypeRepository taskTypes = mock(SourceTaskTypeRepository.class);
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final InternalBillingDirectoryRestApi api = new InternalBillingDirectoryRestApi(TestIdentity.over(this.users, this.tenants), this.taskTypes,
        this.runs, this.definitions, TOKEN);

    @Test
    void withoutTheServiceTokenNothingIsAnswered() {
        assertThat(this.api.usageFacts(null, 2905L).getStatusCode().value()).isEqualTo(401);
        assertThat(this.api.usageFacts("wrong", 2905L).getStatusCode().value()).isEqualTo(401);
        assertThat(this.api.usageFacts(" ", 2905L).getStatusCode().value()).isEqualTo(401);
        assertThat(this.api.runs(null, body(2905L, 8201L)).getStatusCode().value()).isEqualTo(401);
        assertThat(this.api.runs("wrong", body(2905L, 8201L)).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(this.tenants, this.users, this.taskTypes, this.runs);
    }

    @Test
    void anUnsetTokenAdmitsNobody() {
        InternalBillingDirectoryRestApi unset = new InternalBillingDirectoryRestApi(TestIdentity.over(this.users, this.tenants), this.taskTypes,
            this.runs, this.definitions, " ");
        assertThat(unset.usageFacts("", 2905L).getStatusCode().value()).isEqualTo(401);
        assertThat(unset.usageFacts(" ", 2905L).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNightsFactsAreSeatsThatAreNotDeletedAndTopicsInUse() {
        when(this.users.countByTenantIdAndStatusNot(2905L, Status.Delete)).thenReturn(14L);
        when(this.taskTypes.countTopicsInUse(2905L)).thenReturn(3L);

        Map<String, Object> facts = (Map<String, Object>) this.api.usageFacts(TOKEN, 2905L).getBody();

        assertThat(facts).containsEntry("tenantId", 2905L).containsEntry("seats", 14L).containsEntry("topicsInUse", 3L);
    }

    /** A caller who is nobody: whatever the session sees is what the endpoint scoped it to. */
    private static final RowSecurityCaller NOBODY = new RowSecurityCaller() {
        @Override
        public Long tenantId() {
            return null;
        }

        @Override
        public boolean isPlatformAdmin() {
            return false;
        }
    };

    private static Map<String, Object> body(Long tenantId, Long... ids) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("jobQueueIds", Arrays.asList(ids));
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsAreNamedForTheWorkspaceBillingNamesInOneQuery() {
        String[] scoped = {null};
        when(this.runs.findRunNames(eq(2960L), anyString())).thenAnswer(call -> {
            scoped[0] = RowSecurity.current(NOBODY).tenantId();
            return Collections.singletonList(new Object[] {8258L, 2967L, "X-ray triage (inbox)", 1933L, "X-ray triage",
                Timestamp.from(Instant.parse("2026-10-13T01:07:31.619Z")), "Failed"});
        });

        Map<String, Object> answer = (Map<String, Object>) this.api.runs(TOKEN, body(2960L, 8258L, 8258L, 9999L)).getBody();

        verify(this.runs).findRunNames(2960L, "{8258,9999}");
        assertThat(scoped[0]).isEqualTo("2960");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) answer.get("runs");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("jobQueueId", 8258L).containsEntry("jobId", 2967L).containsEntry("schedule", "X-ray triage (inbox)")
            .containsEntry("taskDetailId", 1933L).containsEntry("pipeline", "X-ray triage").containsEntry("startedAt", "2026-10-13T01:07:31.619Z")
            .containsEntry("status", "Failed");
        // No definition: a legacy pipeline says no data class and no tags.
        assertThat(rows.get(0)).containsEntry("sensitivity", null).containsEntry("tags", Collections.emptyList());
    }

    /** Owner #23: what a rate card's pricing rule matches a run on -- its pipeline's data class and tags, from its latest definition. */
    @Test
    @SuppressWarnings("unchecked")
    void eachRunCarriesItsPipelinesDataClassAndTagsForTheRateCardsRules() {
        when(this.runs.findRunNames(eq(2960L), anyString())).thenReturn(Arrays.asList(
            new Object[] {8258L, 2967L, "X-ray triage (inbox)", 1933L, "X-ray triage", null, "Completed"},
            new Object[] {8259L, 2968L, "Claims (daily)", 1934L, "Claims", null, "Completed"}));
        String[] scoped = {null};
        when(this.definitions.latestForTasks(eq(2960L), any())).thenAnswer(call -> {
            scoped[0] = RowSecurity.current(NOBODY).tenantId();
            PipelineDefinitionStore.TaskDefinition xray = new PipelineDefinitionStore.TaskDefinition();
            xray.taskDetailId = 1933L;
            xray.stored = new PipelineDefinitionStore.Stored();
            xray.stored.json = "{\"version\":1,\"steps\":[{\"key\":\"a\",\"task\":\"filter\"}],"
                + "\"settings\":{\"sensitivity\":\"sensitive\",\"tags\":[\"research\",\"pilot\"]}}";
            PipelineDefinitionStore.TaskDefinition claims = new PipelineDefinitionStore.TaskDefinition();
            claims.taskDetailId = 1934L;
            claims.stored = new PipelineDefinitionStore.Stored();
            claims.stored.json = "{\"version\":1,\"steps\":[{\"key\":\"a\",\"task\":\"filter\"}]}";
            return Arrays.asList(xray, claims);
        });

        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) this.api.runs(TOKEN, body(2960L, 8258L, 8259L))
            .getBody()).get("runs");

        assertThat(scoped[0]).isEqualTo("2960");
        assertThat(rows.get(0)).containsEntry("sensitivity", "sensitive").containsEntry("tags", Arrays.asList("research", "pilot"));
        // A definition that says no settings: internal, as the data policy reads it, and no tags.
        assertThat(rows.get(1)).containsEntry("sensitivity", "internal").containsEntry("tags", Collections.emptyList());
    }

    @Test
    void runsNeedAWorkspaceAndAtMostTenThousandIds() {
        assertThat(this.api.runs(TOKEN, null).getStatusCode().value()).isEqualTo(400);
        assertThat(this.api.runs(TOKEN, body(null, 1L)).getStatusCode().value()).isEqualTo(400);
        assertThat(this.api.runs(TOKEN, body(0L, 1L)).getStatusCode().value()).isEqualTo(400);
        Long[] many = new Long[InternalBillingDirectoryRestApi.MAX_RUN_IDS + 1];
        for (int i = 0; i < many.length; i++) {
            many[i] = i + 1L;
        }
        assertThat(this.api.runs(TOKEN, body(2960L, many)).getStatusCode().value()).isEqualTo(400);
        assertThat(this.api.runs(TOKEN, body(2960L)).getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(this.runs);
    }
}
