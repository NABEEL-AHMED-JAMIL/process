package process.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import process.model.enums.Status;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.storage.remote.RemoteStorageDirectory;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentMatchers;
import java.time.Instant;

/**
 * MIG-107: what the platform administrator's workspace list shows that is Core's to count -- Kafka
 * profiles, buckets, task types, tasks, pipelines, jobs -- for a page of workspaces in one call, the
 * same counts process's TenantServiceImpl made per row. Service token only.
 */
class InternalTenantFactsRestApiTest {

    private static final String TOKEN = "t0ken";
    private final KafkaConnectionProfileRepository kafka = mock(KafkaConnectionProfileRepository.class);
    private final RemoteStorageDirectory storage = mock(RemoteStorageDirectory.class);
    private final SourceTaskTypeRepository types = mock(SourceTaskTypeRepository.class);
    private final SourceTaskRepository tasks = mock(SourceTaskRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final InternalTenantFactsRestApi api = new InternalTenantFactsRestApi(this.kafka, this.storage, this.types, this.tasks,
        this.jobs, TOKEN);

    @Test
    void withoutTheServiceTokenNothingIsCounted() {
        assertThat(this.api.tenantFacts(null, Collections.singletonMap("ids", Arrays.asList(1))).getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(this.kafka, this.storage, this.types, this.tasks, this.jobs);
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachWorkspaceGetsTheCountsTheTenantListShowed() {
        when(this.kafka.countByTenantIdAndStatusNot(2901L, Status.Delete)).thenReturn(2L);
        when(this.storage.workspace(2901L)).thenReturn(Arrays.asList(null, null, null));
        when(this.types.countByTenantIdAndStatusNot(2901L, Status.Delete)).thenReturn(4L);
        when(this.tasks.countByTenantIdAndTaskStatusNot(2901L, Status.Delete)).thenReturn(5L);
        when(this.tasks.countDistinctPipelinesByTenantId(2901L, Status.Delete)).thenReturn(6L);
        when(this.jobs.countByTenantIdAndJobStatusNot(2901L, Status.Delete)).thenReturn(7L);

        ResponseEntity<?> answer = this.api.tenantFacts(TOKEN, Collections.singletonMap("ids", Arrays.asList(2901, "x")));

        Map<String, Map<String, Object>> facts = (Map<String, Map<String, Object>>) answer.getBody();
        assertThat(facts).containsOnlyKeys("2901");
        assertThat(facts.get("2901")).containsEntry("kafkaProfileCount", 2L).containsEntry("bucketCount", 3L)
            .containsEntry("sourceTaskTypeCount", 4L).containsEntry("sourceTaskCount", 5L).containsEntry("pipelineCount", 6L)
            .containsEntry("sourceJobCount", 7L);
    }

    @Test
    void moreThanAPageIsRefused() {
        Long[] many = new Long[InternalTenantFactsRestApi.MAX_IDS + 1];
        for (int i = 0; i < many.length; i++) many[i] = (long) i + 1;

        assertThat(this.api.tenantFacts(TOKEN, Collections.singletonMap("ids", Arrays.asList(many))).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(this.kafka, this.storage, this.types, this.tasks, this.jobs);
    }

    @Test
    void aWorkspaceWhoseBucketsCannotBeListedStillGetsItsOtherCounts() {
        when(this.storage.workspace(anyLong())).thenThrow(new IllegalStateException("storage down"));
        when(this.jobs.countByTenantIdAndJobStatusNot(2901L, Status.Delete)).thenReturn(7L);

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> facts = (Map<String, Map<String, Object>>)
            this.api.tenantFacts(TOKEN, Collections.singletonMap("ids", Collections.singletonList(2901))).getBody();

        assertThat(facts.get("2901")).containsEntry("sourceJobCount", 7L).doesNotContainKey("bucketCount");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsThisMonthFailedRunsAndOpenAlertsAreCountedWhenCoreCanCountThem() {
        WorkspaceActivity activity = mock(WorkspaceActivity.class);
        WorkspaceActivity.Figures figures = new WorkspaceActivity.Figures();
        figures.runsThisMonth = 40;
        figures.failedThisMonth = 3;
        figures.openAlerts = 2;
        when(activity.of(ArgumentMatchers.anyCollection(), ArgumentMatchers.any()))
            .thenReturn(Collections.singletonMap(2901L, figures));
        this.api.useActivity(activity);

        Map<String, Map<String, Object>> facts = (Map<String, Map<String, Object>>) this.api.tenantFacts(TOKEN,
            Collections.singletonMap("ids", Arrays.asList(2901, 2902))).getBody();

        assertThat(facts.get("2901")).containsEntry("runsThisMonth", 40L).containsEntry("failedRunsThisMonth", 3L).containsEntry("openAlerts", 2L);
        assertThat(facts.get("2902")).containsEntry("runsThisMonth", 0L).containsEntry("openAlerts", 0L);

        when(activity.of(ArgumentMatchers.anyCollection(), ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("down"));
        facts = (Map<String, Map<String, Object>>) this.api.tenantFacts(TOKEN, Collections.singletonMap("ids", Arrays.asList(2901))).getBody();
        assertThat(facts.get("2901")).doesNotContainKeys("runsThisMonth", "failedRunsThisMonth", "openAlerts");
    }

    @Test
    void theMonthStartsAtChicagosMidnight() {
        assertThat(WorkspaceActivity.monthStart(Instant.parse("2026-10-19T03:00:00Z")))
            .isEqualTo(Instant.parse("2026-10-01T05:00:00Z"));
        assertThat(WorkspaceActivity.monthStart(Instant.parse("2026-11-01T04:00:00Z")))
            .isEqualTo(Instant.parse("2026-10-01T05:00:00Z"));
    }
}
