package process.tenancy;

import process.api.AiModelChoiceRestApi;
import process.api.PipelineDefinitionRestApi;
import process.api.StepTimelineRestApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import process.api.DashboardRestApi;
import process.api.EngineSettingsRestApi;
import process.api.FileChatRestApi;
import process.api.KafkaConnectionProfileRestApi;
import process.api.KafkaSecretRestApi;
import process.api.MessageQRestApi;
import process.api.MeterRestApi;
import process.api.NotifyResetApi;
import process.api.PipelineConfigRestApi;
import process.api.PipelineRestApi;
import process.api.ReportRestApi;
import process.api.RunConfigRestApi;
import process.api.SettingRestApi;
import process.api.SourceJobRestApi;
import process.api.SourceTaskRestApi;
import process.api.TaskReferenceRestApi;
import process.identity.InternalBillingDirectoryRestApi;
import process.identity.InternalIdentityEventsRestApi;
import process.identity.InternalJwksRestApi;
import process.identity.InternalKafkaPublishRestApi;
import process.identity.InternalNotificationRelayRestApi;
import process.identity.InternalPageAccessRestApi;
import process.identity.InternalRunVerificationRestApi;
import process.identity.InternalSecretRestApi;
import process.identity.InternalTenantDirectoryRestApi;
import process.identity.InternalTenantFactsRestApi;
import process.identity.InternalUserDirectoryRestApi;
import process.slo.InternalSloRestApi;
import process.storage.InternalStorageDirectoryRestApi;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-166: the cross-tenant probe covers every Core endpoint, not the ones somebody remembered.
 *
 * Runs in every build, with or without a database. Each request-mapped method on Core's controllers is either probed
 * in one of the CoreCrossTenantProbe*PostgresTest classes (its key appears there as a quoted probe label), or
 * platform-admin only (no tenant reaches it to probe), or listed in {@link #NOTHING_TO_AIM} with the reason it takes
 * nothing a tenant could aim at another workspace.
 *
 * <b>Which controllers.</b> Written out in {@link #CONTROLLERS}, and held equal to what classpath scanning finds --
 * every @RestController or @Controller under process, from main code only -- so a new controller fails this test until
 * it is placed. The scan evaluates @Conditional the way the application does, so the environment switches on
 * everything a controller hangs on (identity.mode=local, the default, for the in-process Identity endpoints); a
 * controller behind a switch that is off would otherwise be skipped silently.
 *
 * <b>What an endpoint is.</b> The mapping is read MERGED (AnnotatedElementUtils): Core declares @RequestMapping on
 * most controllers but @GetMapping/@PostMapping/... on the configuration ones, which Method.getAnnotation(
 * RequestMapping.class) does not see -- and a coverage test that sees no endpoint passes while checking nothing, hence
 * the floor below. An endpoint is keyed "VERB path", because /setting.json/pipelineConfig and
 * /setting.json/taskReferences each answer four verbs from one path. A method mapped to two paths
 * (PipelineRestApi's {/list, /listForms} and friends, aliases kept for the older console) counts as two endpoints:
 * each is a URL a tenant can call, and a later change could split an alias off onto a method of its own, so each is
 * probed by name. A path with no leading slash (SettingRestApi's "xmlCreateChecker") is joined like one with.
 */
class CoreProbeCoverageTest {

    private static final List<Class<?>> CONTROLLERS = Arrays.asList(
        // tenant-facing
        SourceJobRestApi.class, SourceTaskRestApi.class, PipelineRestApi.class, KafkaConnectionProfileRestApi.class,
        KafkaSecretRestApi.class, SettingRestApi.class, PipelineConfigRestApi.class, TaskReferenceRestApi.class,
        EngineSettingsRestApi.class, DashboardRestApi.class, MessageQRestApi.class, ReportRestApi.class, FileChatRestApi.class,
        AiModelChoiceRestApi.class, PipelineDefinitionRestApi.class, StepTimelineRestApi.class,
        // worker callbacks: a run's X-Worker-Token, no tenant principal
        MeterRestApi.class, RunConfigRestApi.class, NotifyResetApi.class,
        // /internal: service token only
        InternalBillingDirectoryRestApi.class, InternalIdentityEventsRestApi.class, InternalJwksRestApi.class,
        InternalKafkaPublishRestApi.class, InternalNotificationRelayRestApi.class, InternalPageAccessRestApi.class,
        InternalRunVerificationRestApi.class, InternalSecretRestApi.class, InternalTenantDirectoryRestApi.class,
        InternalTenantFactsRestApi.class, InternalUserDirectoryRestApi.class, InternalSloRestApi.class,
        InternalStorageDirectoryRestApi.class);

    private static final Map<String, String> NOTHING_TO_AIM = new LinkedHashMap<>();

    static {
        // Worker callbacks (SecurityConfig lets them past the JWT chain): the X-Worker-Token is minted for one run and
        // proves that run, so the caller names no workspace -- the run row does.
        String worker = "worker callback, no tenant principal: the run's X-Worker-Token names the only job and run it may "
            + "report on";
        NOTHING_TO_AIM.put("POST meter.json/verifyRun", "worker's token verified for metering; answers only that run's own "
            + "workspace (MeterVerifyRunContractTest.anyRefusedTokenIsAFlat401InTheSameWords)");
        NOTHING_TO_AIM.put("POST runConfig.json/resolve", "worker's token decides; another run's token is refused before anything "
            + "is read and another workspace's row is never handed out (RunConfigResolverTest."
            + "anotherRunsTokenIsRefusedBeforeAnythingIsRead, aRowOfAnotherWorkspaceIsNeverHandedOut)");
        NOTHING_TO_AIM.put("POST changeState/jobId/{jobId}/jobQueueId/{jobQueueId}/jobStatus/{jobStatus}", worker
            + " (NotifyResetApiTest.aStateChangeWithoutAGoodTokenNeverReachesTheService)");
        NOTHING_TO_AIM.put("POST addLogsBatch/jobId/{jobId}/jobQueueId/{jobQueueId}", worker
            + " (NotifyResetApiTest.logsWithoutAGoodTokenNeverReachTheService)");
        NOTHING_TO_AIM.put("POST addLogs/jobId/{jobId}/jobQueueId/{jobQueueId}", worker
            + " (NotifyResetApiTest.logsWithoutAGoodTokenNeverReachTheService)");

        // File chat's two exports convert text the caller posts; neither reads a bucket, an object, an agent or a row.
        NOTHING_TO_AIM.put("POST fileChat.json/exportFile", "converts the text in the request body; names no stored object, "
            + "agent, job or workspace");
        NOTHING_TO_AIM.put("POST fileChat.json/emailExport", "the same conversion, mailed to the address the caller types; "
            + "names nothing stored");

        // MIG-230: checking a draft definition reads the text posted and the registered tasks; it names no pipeline, run or
        // workspace. (MIG-231: it reads the caller's own workspace's task switches -- from the token, never the body. The
        // task list reads them and the workspace's pipelines too, so it is probed.)
        NOTHING_TO_AIM.put("POST pipeline.json/steps/validate", "parses and checks the definition text in the request body; "
            + "names no stored pipeline, definition or workspace");

        // /internal: the gateway answers 404 for /internal from outside, and each checks X-Internal-Token first.
        String service = "service token only (X-Internal-Token); no tenant reaches it -- the calling service names the tenant";
        NOTHING_TO_AIM.put("POST internal/billingDirectory/tenants", service
            + " (InternalBillingDirectoryRestApiTest.withoutTheServiceTokenNothingIsAnswered)");
        NOTHING_TO_AIM.put("POST internal/billingDirectory/usageFacts", service
            + " (InternalBillingDirectoryRestApiTest.withoutTheServiceTokenNothingIsAnswered)");
        NOTHING_TO_AIM.put("POST internal/billingDirectory/userNames", service
            + " (InternalBillingDirectoryRestApiTest.withoutTheServiceTokenNothingIsAnswered)");
        NOTHING_TO_AIM.put("POST internal/identity/events", service
            + " (InternalIdentityEventsRestApiPostgresTest.withoutTheServiceTokenNothingIsWritten)");
        NOTHING_TO_AIM.put("GET internal/jwks", "Identity's public signing keys, the same answer to anyone -- no token by design, "
            + "and /internal is kept inside by the gateway; names nothing of a tenant's");
        NOTHING_TO_AIM.put("POST internal/kafka/publish", service
            + " (InternalKafkaPublishRestApiTest.withoutTheServiceTokenNothingIsPublished)");
        NOTHING_TO_AIM.put("POST internal/notifications/notice", service
            + " (InternalNotificationRelayRestApiTest.withoutTheServiceTokenNothingIsRelayed)");
        NOTHING_TO_AIM.put("POST internal/notifications/mail", service
            + " (InternalNotificationRelayRestApiTest.withoutTheServiceTokenNothingIsRelayed)");
        NOTHING_TO_AIM.put("POST internal/notifications/forgetRecipient", service
            + " (InternalNotificationRelayRestApiTest.withoutTheServiceTokenNothingIsRelayed)");
        NOTHING_TO_AIM.put("POST internal/pageAccess/check", service
            + " (InternalPageAccessRestApiTest.withoutTheServiceTokenNothingIsAnswered)");
        NOTHING_TO_AIM.put("POST internal/runs/{jobQueueId}/verify-callback", service
            + " (InternalRunVerificationRestApiTest.withoutTheServiceTokenNothingIsAnswered)");
        NOTHING_TO_AIM.put("POST internal/pipelines/countUsingPrompt", service
            + " (InternalRunVerificationRestApiTest.thePromptDeleteGuardCountsThePipelinesUsingAPrompt)");
        NOTHING_TO_AIM.put("POST internal/secretRef/{ref}/redeem", service
            + " (InternalSecretRestApiTest.withoutTheTokenNothingIsRedeemed)");
        NOTHING_TO_AIM.put("POST internal/tenants/resolve", service
            + " (InternalTenantDirectoryRestApiTest.withoutTheServiceTokenNothingIsAnswered)");
        NOTHING_TO_AIM.put("POST internal/core/tenantFacts", service
            + " (InternalTenantFactsRestApiTest.withoutTheServiceTokenNothingIsCounted)");
        NOTHING_TO_AIM.put("POST internal/users/resolve", service
            + " (InternalUserDirectoryRestApiTest.withoutTheServiceTokenNothingIsAnswered)");
        NOTHING_TO_AIM.put("POST internal/slo/runs", service
            + " (InternalSloRestApiTest.noTokenOrTheWrongOneIsRefusedBeforeAnythingIsRead)");
        NOTHING_TO_AIM.put("POST internal/storageDirectory/kafkaReferences", service
            + " (InternalStorageDirectoryRestApiTest.withoutTheInternalTokenNothingIsRead)");
        NOTHING_TO_AIM.put("POST internal/storageDirectory/userNames", service
            + " (InternalStorageDirectoryRestApiTest.withoutTheInternalTokenNothingIsRead)");
    }

    /** One request mapping: its key and the method that answers it. */
    static final class Endpoint {
        final String key;
        final Method method;

        Endpoint(String key, Method method) {
            this.key = key;
            this.method = method;
        }
    }

    /** Every endpoint of a controller, keyed "VERB path", platform-admin-only ones included. */
    static List<Endpoint> endpoints(Class<?> controller) {
        RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        String[] roots = root == null ? new String[0] : (root.value().length > 0 ? root.value() : root.path());
        String prefix = roots.length == 0 ? "" : trim(roots[0]);
        List<Endpoint> found = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            String[] paths = mapping.value().length > 0 ? mapping.value() : mapping.path();
            if (paths.length == 0) {
                paths = new String[] {""};
            }
            List<String> verbs = new ArrayList<>();
            for (RequestMethod verb : mapping.method()) {
                verbs.add(verb.name());
            }
            if (verbs.isEmpty()) {
                verbs.add("ANY");
            }
            for (String verb : verbs) {
                for (String path : paths) {
                    String tail = trim(path);
                    found.add(new Endpoint(verb + " " + (prefix.isEmpty() ? tail : tail.isEmpty() ? prefix : prefix + "/" + tail),
                        method));
                }
            }
        }
        return found;
    }

    private static String trim(String path) {
        return path.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    /** @PreAuthorize on the method, else on the class, is exactly the platform administrator's role. */
    static boolean platformOnly(Class<?> controller, Method method) {
        PreAuthorize own = method.getAnnotation(PreAuthorize.class);
        PreAuthorize rule = own != null ? own : controller.getAnnotation(PreAuthorize.class);
        return rule != null && rule.value().equals("hasRole('PLATFORM_ADMIN')");
    }

    /** Every probe class's source, as one text: the keys are looked for there as quoted labels. */
    static String probeSources() throws IOException {
        StringBuilder text = new StringBuilder();
        List<Path> probes = new ArrayList<>();
        try (DirectoryStream<Path> sources = Files.newDirectoryStream(Paths.get("src/test/java/process/tenancy"),
            "*CrossTenantProbe*PostgresTest.java")) {
            sources.forEach(probes::add);
        }
        assertThat(probes).as("the probe classes, one per controller group").hasSizeGreaterThanOrEqualTo(5);
        for (Path probe : probes) {
            text.append(new String(Files.readAllBytes(probe), StandardCharsets.UTF_8)).append('\n');
        }
        return text.toString();
    }

    /**
     * Probed means a probe call is labelled with the key, followed by the closing quote, a space or an open
     * parenthesis (a variant: "GET pipeline.json/listForms(...)"). The terminator matters: "GET pipeline.json/list"
     * is a prefix of "GET pipeline.json/listForms", and a bare contains() would count one as the other.
     */
    static boolean probed(String sources, String key) {
        return Pattern.compile("\"" + Pattern.quote(key) + "[\"( ]").matcher(sources).find();
    }

    @Test
    void theControllersListedAreEveryControllerCoreHas() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        // Every switch a controller hangs on, on: @IdentityInProcess is identity.mode=local (the default).
        environment.getPropertySources().addFirst(new MapPropertySource("every-controller",
            Collections.<String, Object>singletonMap("identity.mode", "local")));
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false, environment);
        scan.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scan.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<String> found = new TreeSet<>();
        for (BeanDefinition candidate : scan.findCandidateComponents("process")) {
            // Fixture controllers compiled into test-classes (ApiDocsAccessTest's, say) are not Core's.
            String resource = ((AbstractBeanDefinition) candidate).getResourceDescription();
            if (resource != null && resource.contains("test-classes")) {
                continue;
            }
            found.add(candidate.getBeanClassName());
        }
        Set<String> listed = new TreeSet<>();
        CONTROLLERS.forEach(controller -> listed.add(controller.getName()));
        assertThat(found).as("a controller the coverage test does not list: probe it, or list it with a reason").isEqualTo(listed);
    }

    @Test
    void everyEndpointIsProbedPlatformOnlyOrHasNothingToAim() throws IOException {
        String sources = probeSources();
        List<String> found = new ArrayList<>();
        List<String> unplaced = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Endpoint endpoint : endpoints(controller)) {
                found.add(endpoint.key);
                if (!probed(sources, endpoint.key) && !platformOnly(controller, endpoint.method)
                    && !NOTHING_TO_AIM.containsKey(endpoint.key)) {
                    unplaced.add(endpoint.key);
                }
            }
        }
        // The floor: the mappings were read through @GetMapping/@PostMapping, both verbs of one path, both aliases of one
        // method, the path with no slash and the controller with no class mapping.
        assertThat(found).as("Core's endpoints, read merged").contains(
            "POST sourceJob.json/addSourceJob", "GET setting.json/pipelineConfig", "POST setting.json/pipelineConfig",
            "PUT setting.json/pipelineConfig", "DELETE setting.json/pipelineConfig", "GET setting.json/taskReferences",
            "DELETE setting.json/taskReferences", "GET setting.json/engineSettings", "PUT setting.json/engineSettings",
            "POST setting.json/xmlCreateChecker", "GET pipeline.json/list", "GET pipeline.json/listForms",
            "DELETE pipeline.json/deleteForm", "POST runConfig.json/resolve", "GET internal/jwks",
            "POST changeState/jobId/{jobId}/jobQueueId/{jobQueueId}/jobStatus/{jobStatus}");
        // 108 when the probe was written (2026-09-28): a mapping that stopped being read would drop below it.
        assertThat(found).as("every endpoint once").doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(108);
        assertThat(unplaced).as("Core endpoints a tenant can call that no cross-tenant probe calls").isEmpty();
    }

    @Test
    void anExemptionThatNoLongerNamesAnEndpointIsStale() {
        Set<String> all = new HashSet<>();
        CONTROLLERS.forEach(controller -> endpoints(controller).forEach(endpoint -> all.add(endpoint.key)));
        assertThat(all).as("NOTHING_TO_AIM names only endpoints that exist").containsAll(NOTHING_TO_AIM.keySet());
    }

    /** An exemption is for what cannot be probed: an endpoint that is probed, or platform-only, needs none. */
    @Test
    void anExemptionIsNotAlsoProbedOrPlatformOnly() throws IOException {
        String sources = probeSources();
        List<String> redundant = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Endpoint endpoint : endpoints(controller)) {
                if (NOTHING_TO_AIM.containsKey(endpoint.key)
                    && (probed(sources, endpoint.key) || platformOnly(controller, endpoint.method))) {
                    redundant.add(endpoint.key);
                }
            }
        }
        assertThat(redundant).isEmpty();
    }
}
