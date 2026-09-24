package process.model.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-92 (P4, P5) and MIG-93: a read of Identity's tables that crosses tenants is a named grant, visible
 * in review, never an omission.
 *
 * The tenant filter reaches JPQL, not native SQL, so every native query in Identity's repositories
 * either names tenant_id or is named for what it crosses (…AcrossTenants) -- and the list of those is
 * written out here, so adding one is a diff to this file. The two paths the analysis found open by
 * omission are gone: findByUuid (unused) and bare findAll() calls, which read every tenant whenever the
 * filter happened to be off.
 */
class CrossTenantReadsAreNamedTest {

    private static final List<Class<?>> IDENTITY_REPOSITORIES = Arrays.asList(AppUserRepository.class,
        UserPageAccessRepository.class, PageAccessProfileRepository.class, TenantRepository.class,
        TenantRequestRepository.class);

    /** Every cross-tenant read, with why it must cross. */
    private static final Map<String, String> REVIEWED = new HashMap<>();

    static {
        REVIEWED.put("AppUserRepository.findLiveByUsernameAcrossTenants",
            "sign-in: there is no tenant yet, and a name is unique across the platform (MIG-17)");
        REVIEWED.put("AppUserRepository.isUsernameTakenAcrossTenants",
            "whether a name is free is a platform-wide fact (MIG-17)");
        REVIEWED.put("AppUserRepository.findAllByIdAcrossTenants",
            "names for ids: a tenant's row may have been made by a platform admin (MIG-13)");
        REVIEWED.put("AppUserRepository.findAllLiveAcrossTenants", "the platform admin's users screen, through TenantScope.AllTenants");
        REVIEWED.put("AppUserRepository.findActiveByRoleAcrossTenants",
            "telling the platform's admins about a new one; they belong to no tenant");
    }

    /** Native statements keyed by a person's id, or over a table with no tenant, not reads of a tenant's rows. */
    private static final Map<String, String> BY_ID = new HashMap<>();

    static {
        BY_ID.put("AppUserRepository.bumpTokenVersion", "ends one person's tokens; the caller scoped the person first");
        BY_ID.put("AppUserRepository.findTokenVersion", "a number, for the token check; returns no row");
        BY_ID.put("TenantRequestRepository.findOpenByEmail",
            "tenant_request rows belong to no tenant: a request is for a workspace that does not exist yet");
    }

    /** Native queries in process's other repositories that read no tenant's rows, and why. */
    private static final Map<String, String> NOT_TENANT_ROWS = new HashMap<>();

    static {
        String byParent = "keyed by a job, run or task id the caller has already been shown to own";
        for (String name : new String[] {"JobAuditLogRepository.findAllByJobQueueIdV1", "JobAuditLogRepository.updateStatusByJobId",
            "JobQueueRepository.countGroupByJobIds", "JobQueueRepository.getCountForInQueueJobByJobId",
            "JobQueueRepository.getCountForJobByJobId", "JobQueueRepository.updateStatusByJobId", "SourceJobRepository.countLiveJobsForTask",
            "SourceJobRepository.statusChangeSourceJobWithSourceTaskId", "SourceJobRepository.statusChangeSourceJobLinkWithSourceTaskTypeId"}) {
            NOT_TENANT_ROWS.put(name, byParent);
        }
        String self = "keyed by the caller's own app_user_id (the profile screen's own activity)";
        for (String name : new String[] {"JobQueueRepository.countRecentRunsForAssignee", "JobQueueRepository.findRecentRunsForAssignee",
            "SourceJobRepository.countAssignedTo", "SourceJobRepository.outcomesForAssignee"}) {
            NOT_TENANT_ROWS.put(name, self);
        }
        String engine = "the engine's own work across every tenant, run with no caller (scheduler, dispatch, sweeper)";
        for (String name : new String[] {"JobQueueRepository.findAllJobForTodayWithLimit", "JobQueueRepository.findStalledRuns",
            "SchedulerRepository.findDueSchedulers", "JobAuditLogRepository.upsertFromOpenSearch",
            "JobAuditLogRepository.findExistingJobQueueIds", "SourceJobRepository.findNotificationRecipient",
            // core-dispatch's pre-dispatch phase and scheduler claim, and the worker callbacks' refusal record.
            "JobQueueRepository.findRunsToPrepare", "JobQueueRepository.leaseForPreparation", "JobQueueRepository.markPrepared",
            "JobQueueRepository.findOrchestrationSetting", "JobQueueRepository.noteRefusedCallback",
            "JobQueueRepository.findRunsWithRefusedCallbacks", "SchedulerRepository.claimNextDueScheduler"}) {
            NOT_TENANT_ROWS.put(name, engine);
        }
        // FOLLOW-UP for Core: these four are a platform admin's all-tenant lists, chosen by an
        // isPlatformAdmin() branch beside a ...ForTenant twin -- the pattern MIG-92 replaced with
        // ...AcrossTenants and TenantScope in Identity. Renaming them is Core's change to make.
        String platformBranch = "the platform-admin branch of an isPlatformAdmin() choice; the tenant twin is ...ForTenant";
        for (String name : new String[] {"SourceTaskRepository.findAllSourceTask", "SourceTaskRepository.downloadListSourceTask",
            "SourceTaskRepository.fetchAllLinkSourceTaskWithSourceTaskTypeId", "SourceTaskTypeRepository.fetchAllSourceTaskType"}) {
            NOT_TENANT_ROWS.put(name, platformBranch);
        }
        NOT_TENANT_ROWS.put("PipelineRepository.countUsingPrompt", "ai-service's internal question about one prompt id, service token only");
    }

    private static final Pattern TENANT_COLUMN = Pattern.compile("\\btenant_id\\b");

    @Test
    void everyNativeQueryNamesTheTenantOrIsANamedCrossTenantRead() {
        List<String> unnamed = new ArrayList<>();
        TreeSet<String> crossing = new TreeSet<>();
        for (Class<?> repository : IDENTITY_REPOSITORIES) {
            for (Method method : repository.getDeclaredMethods()) {
                String name = repository.getSimpleName() + "." + method.getName();
                if (method.getName().endsWith("AcrossTenants")) {
                    crossing.add(name);
                }
                Query query = method.getAnnotation(Query.class);
                if (query == null || !query.nativeQuery()) {
                    continue;
                }
                if (TENANT_COLUMN.matcher(query.value()).find() || method.getName().endsWith("AcrossTenants")
                    || BY_ID.containsKey(name)) {
                    continue;
                }
                unnamed.add(name);
            }
        }
        assertThat(unnamed).as("native SQL the tenant filter cannot reach, named for nothing it crosses").isEmpty();
        assertThat(crossing).as("the cross-tenant reads, each reviewed above").containsExactlyElementsOf(new TreeSet<>(REVIEWED.keySet()));
    }

    /**
     * MIG-93: the same rule over every repository in process, not only Identity's. A native query the
     * tenant filter cannot reach either names tenant_id, is named for what it crosses, or is listed in
     * {@link #NOT_TENANT_ROWS} with the reason it reads no tenant's rows.
     */
    @Test
    void everyRepositoryInProcessFollowsTheSameRule() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scan.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<String> unnamed = new ArrayList<>();
        for (BeanDefinition bean : scan.findCandidateComponents("process")) {
            Class<?> repository = Class.forName(bean.getBeanClassName());
            for (Method method : repository.getDeclaredMethods()) {
                String name = repository.getSimpleName() + "." + method.getName();
                Query query = method.getAnnotation(Query.class);
                if (query == null || !query.nativeQuery() || TENANT_COLUMN.matcher(query.value()).find()
                    || method.getName().endsWith("AcrossTenants") || BY_ID.containsKey(name) || NOT_TENANT_ROWS.containsKey(name)) {
                    continue;
                }
                unnamed.add(name);
            }
        }
        assertThat(unnamed).as("native SQL the tenant filter cannot reach, named for nothing it crosses").isEmpty();
    }

    @Test
    void theTwoReadsOpenByOmissionAreGone() throws IOException {
        assertThat(Arrays.stream(AppUserRepository.class.getDeclaredMethods()).map(Method::getName))
            .doesNotContain("findByUuid");
        List<String> bareFindAll;
        Pattern call = Pattern.compile("(appUserRepository|users|userRepository|exceptionRepository)\\.findAll\\(\\)");
        try (Stream<Path> walk = Files.walk(Paths.get("src/main/java"))) {
            bareFindAll = walk.filter(f -> f.toString().endsWith(".java")).filter(f -> {
                try {
                    return call.matcher(new String(Files.readAllBytes(f), StandardCharsets.UTF_8)).find();
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }).map(Path::toString).collect(Collectors.toList());
        }
        assertThat(bareFindAll).as("findAll() on app_user or user_page_access: say which tenants instead").isEmpty();
    }
}
