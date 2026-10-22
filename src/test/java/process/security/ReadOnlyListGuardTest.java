package process.security;

import java.nio.charset.StandardCharsets;
import org.springframework.mock.web.MockHttpServletRequest;
import org.barco.platform.security.ManagedAction;
import process.api.PipelineDefinitionRestApi;
import org.barco.platform.contract.ManagedAuditContract;
import org.barco.platform.security.ReadOnlyList;
import org.junit.jupiter.api.Test;
import process.config.ManagementModeConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ZT-M19 (ZT-M10's marks; platform-commons 2.0.10's ManagedAuditContract). Owner, 2026-10-21: "In a managed session, data
 * reads are audited; lists and counts are not." Every POST a person's token reaches here (not /internal, which answers a
 * service token) is exactly one of:
 * <ul>
 *   <li>a write, named in {@link #WRITES} -- a managed session writes its change row before it runs;</li>
 *   <li>a data read, marked {@code @AuditedRead} -- its row says what kind of thing it read, and which;</li>
 *   <li>a list or a count, marked {@link ReadOnlyList} -- exactly {@link #READ_ONLY_LISTS}, no row;</li>
 *   <li>neither -- a validation, a connection test that only says whether it connects -- marked {@code @NoAudit}, no row.</li>
 * </ul>
 * A new POST that is none of these fails here, whatever its name: it is looked at and put in one place before it ships.
 */
class ReadOnlyListGuardTest {

    static final String SERVICE = "process";
    static final String PACKAGE = "process";

    /** Controller.method of every POST that only lists or counts. */
    static final Set<String> READ_ONLY_LISTS = new TreeSet<>(Arrays.asList(
        "BackfillRestApi.preview",
        "FailedRunsRestApi.failedRuns",
        "MessageQRestApi.fetchLogs",
        "MessageQRestApi.runFacets",
        "RetentionRestApi.preview",
        "RunItemRestApi.countRunItems",
        "RunItemRestApi.listItemRuns",
        "RunItemRestApi.listRunItemFiles",
        "RunItemRestApi.listRunItems",
        "RunItemRestApi.selectRunItems",
        "SourceTaskRestApi.fetchAllLinkJobsWithSourceTaskId",
        "SourceTaskRestApi.listSourceTask"));

    /** Controller.method of every POST that changes something, starts or sends something (each looked at, 2026-10-22). */
    static final Set<String> WRITES = new TreeSet<>(Arrays.asList(
        "AiModelChoiceRestApi.runWith",
        "AiModelChoiceRestApi.saveSchedule",
        "AiModelChoiceRestApi.saveStepOptions",
        "AlertRestApi.delete",
        "AlertRestApi.mute",
        "AlertRestApi.resolve",
        "AlertRestApi.save",
        "BackfillRestApi.cancel",
        "BackfillRestApi.pause",
        "BackfillRestApi.resume",
        "BackfillRestApi.retryFailed",
        "BackfillRestApi.start",
        "CustomerBackfillsRestApi.cancel",
        "CustomerBackfillsRestApi.start",
        "CustomerEventsRestApi.send",
        "CustomerPipelinesRestApi.startRun",
        "CustomerRunRetryRestApi.retry",
        "CustomerRunsRestApi.decide",
        "CustomerRunsRestApi.viewLink",
        "DocumentTriggerRestApi.save",
        "EngineSettingsRestApi.reset",
        "EventRouteRestApi.delete",
        "EventRouteRestApi.save",
        "FailedRunsRestApi.bulkRetry",
        "FileChatRestApi.emailExport",
        "FileChatRestApi.endSession",
        "FileChatRestApi.prepareContext",
        "FileChatRestApi.sendMessage",
        "FormRestApi.createShareLink",
        "FormRestApi.rebuildDataset",
        "FormRestApi.revokeShareLink",
        "FormRestApi.save",
        "FormRestApi.setSharePolicy",
        "FormRestApi.status",
        "FormRestApi.submit",
        "FormRestApi.upload",
        "InboxTriggerRestApi.save",
        "KafkaConnectionProfileRestApi.addProfile",
        "KafkaConnectionProfileRestApi.clearDefault",
        "KafkaConnectionProfileRestApi.setAsDefault",
        "KafkaConnectionProfileRestApi.testConnection",
        "KafkaSecretRestApi.generateKeystore",
        "KafkaSecretRestApi.generateTruststore",
        "KafkaSecretRestApi.uploadSecret",
        "MeterRestApi.verifyRun",
        "NotifyResetApi.addLogs",
        "NotifyResetApi.addLogsBatch",
        "NotifyResetApi.changeState",
        "PipelineConfigRestApi.add",
        "PipelineDefinitionRestApi.save",
        "PipelineDefinitionRestApi.switchTask",
        "PipelineRestApi.saveForm",
        "PublicFormRestApi.submit",
        "PublicFormRestApi.upload",
        "RetentionRestApi.confirm",
        "RetentionRestApi.placeHold",
        "RetentionRestApi.releaseHold",
        "RetentionRestApi.run",
        "RetentionRestApi.save",
        "RetentionRestApi.savePlatform",
        "RunConfigRestApi.resolve",
        "RunItemRestApi.retryRunItems",
        "RunItemRestApi.reviewRunItems",
        "RunReviewBulkRestApi.bulkDecide",
        "RunReviewBulkRestApi.savePolicy",
        "RunReviewRestApi.decide",
        "SettingRestApi.addSourceTaskType",
        "SourceJobRestApi.addSourceJob",
        "SourceJobRestApi.askAssistant",
        "SourceJobRestApi.runSourceJob",
        "SourceJobRestApi.skipNextSourceJob",
        "SourceJobRestApi.uploadSourceJob",
        "SourceTaskRestApi.addSourceTask",
        "SourceTaskRestApi.uploadSourceTask",
        "StepTimelineRestApi.retryRun",
        "TaskReferenceRestApi.add",
        "TaskRegistryRestApi.platformState"));

    /** Where the service registers ReplayableBodyFilter, for the reads that name their body's fields. */
    static final List<Class<?>> CONFIGURATIONS = Arrays.asList(ManagementModeConfig.class);

    @Test
    void everyPostIsAListedWriteOrMarkedOnce() {
        ManagedAuditContract.everyPostIsAWriteOrMarked(WRITES, PACKAGE);
    }

    @Test
    void theMarkedListsAreExactlyTheListsAndCounts() {
        Set<String> marked = new TreeSet<>();
        ManagedAuditContract.posts(PACKAGE).forEach((name, posts) -> posts.forEach(p -> {
            if (p.method.isAnnotationPresent(ReadOnlyList.class)) {
                marked.add(name);
            }
        }));
        assertThat(marked).isEqualTo(READ_ONLY_LISTS);
    }

    @Test
    void inAManagedSessionAWriteWritesItsChangeAReadItsKindAndAListOrNoAuditNothing() throws Exception {
        assertThat(ManagedAuditContract.rowsInAManagedSession(SERVICE, WRITES, PACKAGE)).as("POSTs looked at").isPositive();
    }

    @Test
    void theReadsThatNameTheirBodysFieldsHaveTheBodyRead() {
        ManagedAuditContract.bodyFieldsAreRead(CONFIGURATIONS, PACKAGE);
    }

    /** ZT-M19: a step preview is a read named by the task its body runs; a validation of a definition writes no row. */
    @Test
    void aStepPreviewIsARowNamedByItsTaskAndAValidationNone() throws Exception {
        MockHttpServletRequest preview = new MockHttpServletRequest("POST", "/api/v1/pipeline.json/steps/preview");
        preview.setContextPath("/api/v1");
        preview.setContentType("application/json");
        preview.setContent("{\"task\":\"compute\",\"config\":{},\"rows\":[{\"city\":\"Leeds\"}]}".getBytes(StandardCharsets.UTF_8));
        List<ManagedAction> rows = ManagedAuditContract.rows(SERVICE, PipelineDefinitionRestApi.class, "preview", preview);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isRead()).isTrue();
        assertThat(rows.get(0).getReadKind()).isEqualTo("step preview");
        assertThat(rows.get(0).getReadId()).isEqualTo("compute");

        MockHttpServletRequest validate = new MockHttpServletRequest("POST", "/api/v1/pipeline.json/steps/validate");
        validate.setContextPath("/api/v1");
        assertThat(ManagedAuditContract.rows(SERVICE, PipelineDefinitionRestApi.class, "validate", validate)).isEmpty();
    }
}
