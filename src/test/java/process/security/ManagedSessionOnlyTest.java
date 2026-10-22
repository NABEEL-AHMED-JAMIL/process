package process.security;

import org.barco.platform.security.AuditedRead;
import org.barco.platform.security.CallerIdentity;
import org.barco.platform.security.ManagedAction;
import org.barco.platform.security.ManagementMode;
import org.barco.platform.security.ManagementModeInterceptor;
import org.barco.platform.security.TenantContext;
import org.barco.platform.security.WorkspaceDataGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import process.config.ManagementModeConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ZT-A12 / ZT-D9 follow-up (owner 2026-10-21, "Managed session only" and "Audit data reads"), on every controller of the
 * service through the interceptors as ManagementModeConfig registers them:
 * <ul>
 *   <li>the platform administrator's own sign-in (PLATFORM_ADMIN, no workspace) is let through to exactly the
 *       platform-level handlers below and refused (403) everywhere else that a person reaches (/internal answers a
 *       service token, and is left out unless it is marked);</li>
 *   <li>a managed-service session ("Work in this workspace") is let through everywhere;</li>
 *   <li>the handlers that open, export or download a workspace's data are exactly the @AuditedRead ones below, and in a
 *       managed session each writes one managed_action_log row with its kind; a list writes none.</li>
 * </ul>
 */
class ManagedSessionOnlyTest {

    /** Controller.method a platform administrator's own sign-in may call: no workspace's data. */
    static final Set<String> PLATFORM_LEVEL = new TreeSet<>(Arrays.asList("AlertRestApi.delete",
        "AlertRestApi.incidents",
        "AlertRestApi.list",
        "AlertRestApi.mute",
        "AlertRestApi.resolve",
        "AlertRestApi.save",
        "DashboardRestApi.jobRunningStatistics",
        "DashboardRestApi.jobStatusStatistics",
        "DashboardRestApi.weeklyHrsRunningJobStatistics",
        "DashboardRestApi.weeklyRunningJobStatistics",
        "EngineSettingsRestApi.history",
        "EngineSettingsRestApi.list",
        "EngineSettingsRestApi.reset",
        "EngineSettingsRestApi.update",
        "EventRouteRestApi.delete",
        "EventRouteRestApi.list",
        "EventRouteRestApi.save",
        "EventRouteRestApi.targets",
        "HealthRestApi.health",
        "KafkaConnectionProfileRestApi.addProfile",
        "KafkaConnectionProfileRestApi.clearDefault",
        "KafkaConnectionProfileRestApi.deleteProfile",
        "KafkaConnectionProfileRestApi.fetchAllProfiles",
        "KafkaConnectionProfileRestApi.setAsDefault",
        "KafkaConnectionProfileRestApi.testConnection",
        "KafkaConnectionProfileRestApi.testTopic",
        "KafkaConnectionProfileRestApi.updateProfile",
        "KafkaSecretRestApi.generateKeystore",
        "KafkaSecretRestApi.generateTruststore",
        "KafkaSecretRestApi.uploadSecret",
        "PipelineConfigRestApi.add",
        "PipelineConfigRestApi.delete",
        "PipelineConfigRestApi.list",
        "PipelineConfigRestApi.update",
        "PipelineDefinitionRestApi.reads",
        "PipelineDefinitionRestApi.tasks",
        "PipelineRestApi.count",
        "ReliabilityRestApi.burnRates",
        "ReliabilityRestApi.pipelineRuns",
        "RetentionRestApi.audit",
        "RetentionRestApi.confirm",
        "RetentionRestApi.passes",
        "RetentionRestApi.placeHold",
        "RetentionRestApi.platform",
        "RetentionRestApi.preview",
        "RetentionRestApi.releaseHold",
        "RetentionRestApi.run",
        "RetentionRestApi.save",
        "RetentionRestApi.savePlatform",
        "RetentionRestApi.view",
        "SettingRestApi.addSourceTaskType",
        "SettingRestApi.appSetting",
        "SettingRestApi.deleteKafkaRoute",
        "SettingRestApi.deleteSourceTaskType",
        "SettingRestApi.fetchKafkaRoute",
        "SettingRestApi.profileHealth",
        "SettingRestApi.setKafkaRoute",
        "SettingRestApi.topics",
        "SettingRestApi.topicsForProfile",
        "SettingRestApi.updateSourceTaskType",
        "SettingRestApi.xmlCreateChecker",
        "SourceTaskRestApi.names",
        "TaskReferenceRestApi.add",
        "TaskReferenceRestApi.delete",
        "TaskReferenceRestApi.list",
        "TaskReferenceRestApi.update",
        "TaskRegistryRestApi.history",
        "TaskRegistryRestApi.platformState",
        "TaskRegistryRestApi.usage"));

    /** Controller.method -> the kind each @AuditedRead handler writes. */
    static final Map<String, String> AUDITED = new TreeMap<>();

    static {
        AUDITED.put("StepTimelineRestApi.runOutputs", "run outputs");
        AUDITED.put("StepTimelineRestApi.runDataset", "run output");
        AUDITED.put("StepTimelineRestApi.stepLogs", "step logs");
        AUDITED.put("SourceJobRestApi.findSourceJobAuditLog", "run log");
        AUDITED.put("FormSubmissionRestApi.fetch", "form submission");
        AUDITED.put("FormSubmissionRestApi.export", "form submissions CSV");
        AUDITED.put("SourceJobRestApi.downloadListSourceJob", "schedules export");
        AUDITED.put("SourceTaskRestApi.downloadListSourceTask", "pipelines export");
        // MIG-420: a run item, its step's data, a file, the item's files as a zip, the items as CSV.
        AUDITED.put("RunItemRestApi.detail", "run item");
        AUDITED.put("RunItemRestApi.data", "run item data");
        AUDITED.put("RunItemRestApi.file", "run item file");
        AUDITED.put("RunItemRestApi.zip", "run item files (zip)");
        AUDITED.put("RunItemRestApi.csv", "run items (CSV)");
        // MIG-421: one item in two runs compared, step by step (its data both times).
        AUDITED.put("RunItemRestApi.compare", "run item compare");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        ManagementMode.clear();
    }

    private static List<HandlerInterceptor> registered() {
        List<HandlerInterceptor> registered = new ArrayList<>();
        new ManagementModeConfig("http://identity:9160", "").addInterceptors(new InterceptorRegistry() {
            @Override
            public InterceptorRegistration addInterceptor(HandlerInterceptor i) {
                registered.add(i);
                return super.addInterceptor(i);
            }
        });
        return registered;
    }

    /** Every handler a person's token reaches, as Controller.method -> (HTTP method, path, Method). */
    private static Map<String, Object[]> handlers() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Map<String, Object[]> found = new TreeMap<>();
        for (BeanDefinition candidate : scan.findCandidateComponents("process")) {
            if (candidate.getResourceDescription() != null && candidate.getResourceDescription().contains("test-classes")) {
                continue;
            }
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String path = prefix + (mapping.value().length == 0 ? "" : mapping.value()[0]);
                String verb = mapping.method().length == 0 ? "POST" : mapping.method()[0].name();
                found.put(controller.getSimpleName() + "." + method.getName(), new Object[] {verb, path, method, controller});
            }
        }
        return found;
    }

    private static Map<String, Boolean> letThrough(CallerIdentity caller) throws Exception {
        HandlerInterceptor guard = registered().stream().filter(i -> i instanceof WorkspaceDataGuard).findFirst()
            .orElseThrow(() -> new AssertionError("WorkspaceDataGuard is not registered"));
        ManagementMode.set(caller);
        Map<String, Boolean> answered = new TreeMap<>();
        for (Map.Entry<String, Object[]> h : handlers().entrySet()) {
            Object[] v = h.getValue();
            MockHttpServletRequest request = new MockHttpServletRequest((String) v[0], "/api/v1" + v[1]);
            request.setContextPath("/api/v1");
            answered.put(h.getKey(), guard.preHandle(request, new MockHttpServletResponse(),
                new HandlerMethod(mock((Class<?>) v[3]), (Method) v[2])));
        }
        return answered;
    }

    @Test
    void theOwnSignInReachesThePlatformLevelHandlersOnly() throws Exception {
        Map<String, Boolean> answered = letThrough(new CallerIdentity(4639L, null, "PLATFORM_ADMIN", "qa", false));

        Map<String, Object[]> all = handlers();
        Set<String> let = new TreeSet<>();
        answered.forEach((name, go) -> {
            // Core: no person's token reaches these (SecurityConfig, JwtAuthenticationFilter), so there is nothing here to
            // refuse -- /internal a service token, /customer an API client's (or a signed link), the worker callbacks the
            // run's X-Worker-Token, /publicForm.json a share link.
            if (go && !reachedWithoutAPerson((String) all.get(name)[1])) {
                let.add(name);
            }
        });
        assertThat(let).as("what the platform administrator's own sign-in still reaches").isEqualTo(PLATFORM_LEVEL);
    }

    private static final List<String> NO_PERSON = Arrays.asList("/internal", "/customer", "/changeState", "/addLogs",
        "/addLogsBatch", "/runConfig.json", "/meter.json", "/publicForm.json");

    private static boolean reachedWithoutAPerson(String path) {
        return NO_PERSON.stream().anyMatch(path::startsWith);
    }

    @Test
    void aManagedSessionIsLetThroughEverywhere() throws Exception {
        Map<String, Boolean> answered = letThrough(new CallerIdentity(4639L, 2962L, "TENANT_ADMIN", "qa", false, "jti", 1,
            "MANAGED", true));

        assertThat(answered.values()).doesNotContain(false);
    }

    @Test
    void theDataReadsAreAuditedInAManagedSessionAndNothingElseThatIsAGet() throws Exception {
        assertThat(registered()).anyMatch(i -> i instanceof ManagementModeInterceptor);
        Map<String, String> marked = new TreeMap<>();
        handlers().forEach((name, v) -> {
            AuditedRead read = ((Method) v[2]).getAnnotation(AuditedRead.class);
            if (read != null) {
                marked.put(name, read.value());
            }
        });
        assertThat(marked).isEqualTo(AUDITED);

        List<ManagedAction> rows = new ArrayList<>();
        ManagementModeInterceptor audit = new ManagementModeInterceptor("process", rows::add);
        int gets = 0;
        int audited = 0;
        for (Map.Entry<String, Object[]> h : handlers().entrySet()) {
            Object[] v = h.getValue();
            if (!"GET".equals(v[0])) {
                continue;
            }
            gets++;
            // A token per handler: two handlers that read the same kind and id are one read in one session.
            CallerIdentity managed = new CallerIdentity(4639L, 2962L, "TENANT_ADMIN", "qa", false, "jti-" + h.getKey(), 1,
                "MANAGED", true);
            ManagementMode.set(managed);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1" + v[1]);
            request.setContextPath("/api/v1");
            int before = rows.size();
            assertThat(audit.preHandle(request, new MockHttpServletResponse(), new HandlerMethod(mock((Class<?>) v[3]), (Method) v[2])))
                .isTrue();
            if (AUDITED.containsKey(h.getKey())) {
                audited++;
                assertThat(rows).as(h.getKey()).hasSize(before + 1);
                assertThat(rows.get(before).getReadKind()).isEqualTo(AUDITED.get(h.getKey()));
                assertThat(rows.get(before).getTenantId()).isEqualTo(2962L);
            } else {
                assertThat(rows).as(h.getKey() + " is a list, a count or a platform read: no row").hasSize(before);
            }
        }
        assertThat(gets).isGreaterThan(audited);
    }
}
