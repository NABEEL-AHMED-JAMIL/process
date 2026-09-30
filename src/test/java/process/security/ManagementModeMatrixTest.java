package process.security;

import org.barco.platform.security.BuilderAction;
import org.barco.platform.security.CallerIdentity;
import org.barco.platform.security.ManagedAction;
import org.barco.platform.security.ManagementMode;
import org.barco.platform.security.ManagementModeInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import process.api.AiModelChoiceRestApi;
import process.api.PipelineRestApi;
import process.identity.IdentityPort;
import process.api.RunReviewRestApi;
import process.api.SourceJobRestApi;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-244: process's permission matrix for the management mode.
 *
 * Every write a person can reach in process is either a {@link BuilderAction} -- building or running a pipeline: the
 * customer may not in a MANAGED workspace, our managed-service staff and platform administrators may -- or listed in
 * {@link #CUSTOMERS_IN_EITHER_MODE} with the reason the customer keeps it (viewing, reviewing, downloading, asking).
 * A write added later fails this test until it is put on one side. In a SELF workspace nothing changes: the
 * endpoint's own role and page checks decide, as before.
 */
class ManagementModeMatrixTest {

    /** Build or run: refused to the customer's own people in a MANAGED workspace. */
    static final Set<String> BUILDER_ACTIONS = new TreeSet<>(Arrays.asList(
        "AiModelChoiceRestApi.saveSchedule", "AiModelChoiceRestApi.saveStepOptions",
        "FormRestApi.save", "FormRestApi.status",
        "InboxTriggerRestApi.delete", "InboxTriggerRestApi.save",
        "KafkaConnectionProfileRestApi.addProfile", "KafkaConnectionProfileRestApi.clearDefault",
        "KafkaConnectionProfileRestApi.deleteProfile", "KafkaConnectionProfileRestApi.setAsDefault",
        "KafkaConnectionProfileRestApi.updateProfile",
        "KafkaSecretRestApi.generateKeystore", "KafkaSecretRestApi.generateTruststore", "KafkaSecretRestApi.uploadSecret",
        "MessageQRestApi.changeJobStatus", "MessageQRestApi.failJobLogs", "MessageQRestApi.interruptJobLogs",
        "PipelineConfigRestApi.add", "PipelineConfigRestApi.delete", "PipelineConfigRestApi.update",
        "PipelineDefinitionRestApi.save", "PipelineDefinitionRestApi.switchTask",
        "PipelineRestApi.deleteForm", "PipelineRestApi.saveForm",
        "SettingRestApi.addSourceTaskType", "SettingRestApi.deleteKafkaRoute", "SettingRestApi.deleteSourceTaskType",
        "SettingRestApi.setKafkaRoute", "SettingRestApi.updateSourceTaskType",
        "SourceJobRestApi.addSourceJob", "SourceJobRestApi.deleteSourceJob",
        "SourceJobRestApi.skipNextSourceJob", "SourceJobRestApi.toggleSourceJobStatus", "SourceJobRestApi.updateSourceJob",
        "SourceJobRestApi.uploadSourceJob",
        "SourceTaskRestApi.addSourceTask", "SourceTaskRestApi.deleteSourceTask", "SourceTaskRestApi.updateSourceTask",
        "SourceTaskRestApi.uploadSourceTask",
        "TaskReferenceRestApi.add", "TaskReferenceRestApi.delete", "TaskReferenceRestApi.update"));

    /** Writes (not GET) the customer keeps in both modes, each with why. */
    static final Map<String, String> CUSTOMERS_IN_EITHER_MODE = new TreeMap<>();

    static {
        String read = "a read sent as a POST: it changes nothing";
        CUSTOMERS_IN_EITHER_MODE.put("RunReviewRestApi.decide", "review: the customer approves or rejects a run's output (MIG-237)");
        CUSTOMERS_IN_EITHER_MODE.put("ReportRestApi.export", "download");
        // Wave 5 Forms (lite): filling in a form the workspace was given is data entry, not building -- even when the form
        // starts a job, as Run now and the inbox upload do for an existing schedule.
        CUSTOMERS_IN_EITHER_MODE.put("FormRestApi.submit", "filling in a form: data entry, which may start the job the form names");
        CUSTOMERS_IN_EITHER_MODE.put("FormRestApi.upload", "a file or signature for a form being filled in: data entry (MIG-277)");
        // Owner 2026-09-29: running an existing schedule is not building it; a managed customer reruns with a new file.
        CUSTOMERS_IN_EITHER_MODE.put("SourceJobRestApi.runSourceJob", "running an existing schedule (Run now)");
        CUSTOMERS_IN_EITHER_MODE.put("AiModelChoiceRestApi.runWith", "running an existing schedule once with other inputs (Run with)");
        CUSTOMERS_IN_EITHER_MODE.put("FileChatRestApi.prepareContext", "asking about their own files");
        CUSTOMERS_IN_EITHER_MODE.put("FileChatRestApi.sendMessage", "asking about their own files");
        CUSTOMERS_IN_EITHER_MODE.put("FileChatRestApi.endSession", "asking about their own files");
        CUSTOMERS_IN_EITHER_MODE.put("FileChatRestApi.exportFile", "download");
        CUSTOMERS_IN_EITHER_MODE.put("FileChatRestApi.emailExport", "download, by mail to themselves");
        CUSTOMERS_IN_EITHER_MODE.put("SourceJobRestApi.askAssistant", "asking the assistant about a run");
        CUSTOMERS_IN_EITHER_MODE.put("MessageQRestApi.fetchLogs", read);
        CUSTOMERS_IN_EITHER_MODE.put("SourceTaskRestApi.listSourceTask", read);
        CUSTOMERS_IN_EITHER_MODE.put("SourceTaskRestApi.fetchAllLinkJobsWithSourceTaskId", read);
        CUSTOMERS_IN_EITHER_MODE.put("PipelineDefinitionRestApi.validate", "checks a definition, saves nothing");
        CUSTOMERS_IN_EITHER_MODE.put("SettingRestApi.xmlCreateChecker", "checks a document, saves nothing");
        CUSTOMERS_IN_EITHER_MODE.put("KafkaConnectionProfileRestApi.testConnection", "tries a connection, saves nothing");
        CUSTOMERS_IN_EITHER_MODE.put("RunConfigRestApi.resolve", "resolves a run's configuration, saves nothing");
        CUSTOMERS_IN_EITHER_MODE.put("MeterRestApi.verifyRun", "checks a run's metering, saves nothing");
        CUSTOMERS_IN_EITHER_MODE.put("EngineSettingsRestApi.update", "platform administrators only (unchanged)");
        CUSTOMERS_IN_EITHER_MODE.put("NotifyResetApi.changeState", "the worker's callback, not a person's (RunCallbackTokens)");
        CUSTOMERS_IN_EITHER_MODE.put("NotifyResetApi.addLogsBatch", "the worker's callback, not a person's (RunCallbackTokens)");
        CUSTOMERS_IN_EITHER_MODE.put("NotifyResetApi.addLogs", "the worker's callback, not a person's (RunCallbackTokens)");
    }

    private final List<ManagedAction> audited = new ArrayList<>();
    private final ManagementModeInterceptor interceptor = new ManagementModeInterceptor("process", this.audited::add);

    @AfterEach
    void clear() {
        ManagementMode.clear();
    }

    /** Every person-facing controller's handlers: Controller.method, with whether it is marked and whether it writes. */
    private static Map<String, boolean[]> handlers() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, boolean[]> found = new TreeMap<>();
        for (BeanDefinition candidate : scan.findCandidateComponents("process")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            if (controller.getSimpleName().startsWith("Internal") || candidate.getResourceDescription().contains("test-classes")) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                boolean write = mapping.method().length == 0 || Arrays.stream(mapping.method()).anyMatch(m -> m != RequestMethod.GET);
                boolean marked = method.isAnnotationPresent(BuilderAction.class) || controller.isAnnotationPresent(BuilderAction.class);
                found.put(controller.getSimpleName() + "." + method.getName(), new boolean[] {marked, write});
            }
        }
        return found;
    }

    @Test
    void theBuilderActionsAreExactlyTheMatrixSays() throws Exception {
        Set<String> marked = new TreeSet<>();
        handlers().forEach((name, flags) -> {
            if (flags[0]) marked.add(name);
        });

        assertThat(marked).isEqualTo(BUILDER_ACTIONS);
    }

    @Test
    void everyWriteIsABuilderActionOrTheCustomersInEitherModeForAReason() throws Exception {
        List<String> unplaced = new ArrayList<>();
        handlers().forEach((name, flags) -> {
            if (flags[1] && !flags[0] && !CUSTOMERS_IN_EITHER_MODE.containsKey(name)) unplaced.add(name);
        });

        assertThat(unplaced).as("writes on neither side of the management mode").isEmpty();
        assertThat(CUSTOMERS_IN_EITHER_MODE.keySet()).doesNotContainAnyElementsOf(BUILDER_ACTIONS);
    }

    // ------------------------------------------------------------------------ the matrix, through the interceptor

    private static void caller(String role, String mode, boolean staff) {
        ManagementMode.set(new CallerIdentity(4385L, "PLATFORM_ADMIN".equals(role) ? null : 2905L, role, "who@example.test", false,
            "jti", 0, mode, staff));
    }

    /** What the interceptor answers this caller for this handler: 200 when it lets the request through. */
    private int status(String httpMethod, Class<?> controller, String method) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, "/api/v1/x");
        request.setContextPath("/api/v1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = new HandlerMethod(mock(controller), methodNamed(controller, method));
        return this.interceptor.preHandle(request, response, handler) ? 200 : response.getStatus();
    }

    @Test
    void savingAPipelineByMode() throws Exception {
        caller("TENANT_ADMIN", "SELF", false);
        assertThat(this.status("POST", PipelineRestApi.class, "saveForm")).as("customer admin, SELF").isEqualTo(200);
        caller("TENANT_ADMIN", "MANAGED", false);
        assertThat(this.status("POST", PipelineRestApi.class, "saveForm")).as("customer admin, MANAGED").isEqualTo(403);
        caller("TENANT_USER", "MANAGED", false);
        assertThat(this.status("POST", PipelineRestApi.class, "saveForm")).as("customer user, MANAGED").isEqualTo(403);
        caller("PLATFORM_ADMIN", null, false);
        assertThat(this.status("POST", PipelineRestApi.class, "saveForm")).as("platform admin").isEqualTo(200);
        assertThat(this.audited).isEmpty();
        caller("TENANT_ADMIN", "MANAGED", true);
        assertThat(this.status("POST", PipelineRestApi.class, "saveForm")).as("managed-service staff, MANAGED").isEqualTo(200);
        assertThat(this.audited).hasSize(1);
        assertThat(this.audited.get(0).getAction()).endsWith(".saveForm");
        assertThat(this.audited.get(0).isBuilderAction()).isTrue();
        assertThat(this.audited.get(0).getAppUserId()).isEqualTo(4385L);
        assertThat(this.audited.get(0).getService()).isEqualTo("process");
    }

    @Test
    void theCustomerViewsReviewsAndRunsButDoesNotBuildInAManagedWorkspace() throws Exception {
        caller("TENANT_ADMIN", "MANAGED", false);

        assertThat(this.status("GET", SourceJobRestApi.class, "listSourceJob")).as("view").isEqualTo(200);
        assertThat(this.status("POST", RunReviewRestApi.class, "decide")).as("review").isEqualTo(200);
        assertThat(this.status("POST", SourceJobRestApi.class, "runSourceJob")).as("run now").isEqualTo(200);
        assertThat(this.status("POST", AiModelChoiceRestApi.class, "runWith")).as("run with").isEqualTo(200);
        assertThat(this.status("POST", SourceJobRestApi.class, "skipNextSourceJob")).as("skip next").isEqualTo(403);
        assertThat(this.status("PUT", SourceJobRestApi.class, "updateSourceJob")).as("schedule").isEqualTo(403);
    }

    private static Method methodNamed(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError(type.getSimpleName() + " has no " + name));
    }

    /** process's own bearer filter hands the token's mode and staff flag to platform-commons, for this request only. */
    @Test
    void theFilterSetsTheModeForTheRequestOnly() throws Exception {
        IdentityPort identity = mock(IdentityPort.class);
        when(identity.authenticate("t")).thenReturn(Optional.of(new CallerIdentity(7L, 2905L,
            "TENANT_ADMIN", "staff@ours.example", false, "jti", 0, "MANAGED", true)));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pipeline.json/save");
        request.addHeader("Authorization", "Bearer t");
        List<CallerIdentity> seen = new ArrayList<>();

        new JwtAuthenticationFilter(identity).doFilter(request, new MockHttpServletResponse(),
            (req, res) -> seen.add(ManagementMode.current()));

        assertThat(seen.get(0).isManagedService()).isTrue();
        assertThat(seen.get(0).getManagementMode()).isEqualTo("MANAGED");
        assertThat(ManagementMode.current()).isNull();
        SecurityContextHolder.clearContext();
    }
}
