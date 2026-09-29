package process.tenancy;

import process.ai.AiModelChoiceService;
import process.ai.AiPort;
import process.ai.JdbcModelChoiceStore;
import process.api.AiModelChoiceRestApi;
import process.api.InboxTriggerRestApi;
import process.inbox.InboxTriggerService;
import process.inbox.JdbcInboxTriggerStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.api.DashboardRestApi;
import process.api.FileChatRestApi;
import process.api.KafkaConnectionProfileRestApi;
import process.api.KafkaSecretRestApi;
import process.api.MessageQRestApi;
import process.api.PipelineConfigRestApi;
import process.api.PipelineRestApi;
import process.api.PipelineDefinitionRestApi;
import process.api.RunReviewRestApi;
import process.api.StepTimelineRestApi;
import process.pipeline.JdbcStepStore;
import process.pipeline.FileAccessLog;
import process.pipeline.FileDatasetStore;
import process.pipeline.StepTimelineService;
import process.pipeline.review.JdbcRunReviewStore;
import process.pipeline.review.RunReviewService;
import process.pipeline.review.RunReviews;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import process.pipeline.DefinitionValidator;
import process.pipeline.Definitions;
import process.pipeline.PipelineDefinitionService;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepTasks;
import process.pipeline.registry.JdbcTaskOverrideStore;
import process.pipeline.registry.TaskRegistry;
import process.api.ReportRestApi;
import process.api.SettingRestApi;
import process.api.SourceJobRestApi;
import process.api.SourceTaskRestApi;
import process.api.TaskReferenceRestApi;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.engine.BulkAction;
import process.engine.ProducerBulkEngine;
import process.filechat.FileIndexLock;
import process.identity.IdentityPort;
import process.identity.TestIdentity;
import process.media.MediaPort;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.pojo.StorageConnection;
import process.model.repository.AppUserRepository;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.PipelineConfigRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.repository.TenantRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.AiAgentService;
import process.model.service.EmbeddingService;
import process.model.service.impl.DashboardServiceImpl;
import process.model.service.impl.FileChatServiceImpl;
import process.model.service.impl.JobAssistantServiceImpl;
import process.model.service.impl.KafkaConnectionProfileServiceImpl;
import process.model.service.impl.KafkaSecretServiceImpl;
import process.model.service.impl.MessageQServiceImpl;
import process.model.service.impl.PipelineServiceImpl;
import process.model.service.impl.QueryService;
import process.model.service.impl.ReportExportServiceImpl;
import process.model.service.impl.SettingServiceImpl;
import process.model.service.impl.SourceJobBulkServiceImpl;
import process.model.service.impl.SourceJobServiceImpl;
import process.model.service.impl.SourceTaskServiceImpl;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.settings.PipelineConfigService;
import process.settings.TaskConfigRules;
import process.settings.TaskReferenceService;
import process.storage.TrustedStorageOperations;
import process.storage.remote.HttpStorageBrowser;
import process.storage.remote.RemoteStorageDirectory;
import process.storage.remote.StorageServiceClient;
import process.util.EncryptionUtil;
import process.util.OpenSearchAuditLogClient;
import process.util.OpenSearchRagClient;
import process.util.ProcessUtil;
import process.util.TaskPayloadLocationUtil;
import process.util.UserNameResolver;
import process.util.XmlOutTagInfoUtil;
import process.util.excel.BulkExcel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The world every Core cross-tenant probe runs in (MIG-166): a scratch etl_job built by the real changelog, three
 * workspaces' worth of rows, and Core's real services and controllers wired by hand over it -- no Spring context, so
 * what is exercised is the code a request reaches, with the tenant filter and the job-ownership filter turned on by
 * the same TenantFilterHelper a request uses.
 *
 * <b>Why one fixture for five probe classes.</b> Core has about a hundred endpoints a tenant can reach. One probe
 * class per controller group keeps each readable, and every group needs the same world: two workspaces whose rows
 * differ only in who owns them, a colleague in A (a tenant user sees only the jobs that name them, owner decision
 * 2026-09-24, so the probe has to include a job of the same workspace that is not the caller's), and the platform's
 * own rows (kafka_connection_profile keeps tenant_id NULL rows on purpose). Each class builds its own copy -- its
 * own scratch database -- so one class's legitimate writes cannot change what the next one sees.
 *
 * <b>What counts as a leak.</b> Every row of B's carries a distinctive MARKER (a name, a value, a message) that A's
 * callers never send, so finding one in a serialised answer can only mean the answer carries B's row. Ids are not
 * markers: the probe sends B's ids itself, and a refusal that repeats the id it was given has told the caller
 * nothing. {@link #probe} serialises every answer with Jackson inside the call's transaction (lazy rows would
 * otherwise be skipped rather than read), opens a workbook download and reads its XML, and records each marker it
 * finds. {@link #foreignRows} is the other half: B's rows, the platform's rows and the colleague's job, rendered
 * whole, so a probe can prove nothing of theirs changed.
 *
 * <b>Collaborators</b> are Mockito mocks the probes verify: the job engine, the audit writer, the notification port,
 * mail, the AI service, the Kafka client factory, the trusted storage path. Storage as the signed-in user is the
 * exception: file chat and report export reach storage-service over HTTP with the caller's own bearer token, and a
 * mock would hide exactly that. A {@link FakeStorage} on a loopback port stands in for storage-service -- it knows
 * which buckets each token may use, refuses the rest as storage does, and records every request with the
 * Authorization header it carried.
 *
 * Opt-in like every Postgres test here: ScratchPostgres skips the calling class when NOTIFICATIONS_TEST_DB_URL is not
 * set.
 */
final class CoreProbeFixture implements AutoCloseable {

    // ---- workspaces ------------------------------------------------------------------------------------------

    static final long A = 6601L;
    static final long B = 6602L;
    /** A third workspace with no Kafka connection of its own: the one the platform default is shown to. */
    static final long C = 6603L;
    /** Deleted in Identity: a platform administrator's write must not be able to name it. */
    static final long GONE = 6604L;
    /** The workspace coded "default" (V69.0's backfill target), which a caller with no workspace must not reach. */
    static final long DEFAULT_WS = 6605L;
    /** No tenant row at all. */
    static final long UNKNOWN = 6699L;

    // ---- people ----------------------------------------------------------------------------------------------

    static final long ROOT = 7600L;
    static final long ADMIN_A = 7601L;
    static final long USER_A = 7602L;
    static final long COLLEAGUE_A = 7603L;
    static final long ADMIN_B = 7611L;
    static final long USER_B = 7612L;
    static final long ADMIN_C = 7621L;
    /** A token with a role but no workspace: a legacy or broken app_user row. Nobody has this id. */
    static final long ORPHAN = 7699L;

    // ---- rows ------------------------------------------------------------------------------------------------

    static final long PLATFORM_PROFILE = 8600L;
    static final long A_PROFILE = 8601L;
    static final long B_PROFILE = 8602L;
    static final long A_TYPE = 8611L;
    static final long B_TYPE = 8612L;
    static final long C_TYPE = 8613L;
    static final long DEFAULT_TYPE = 8615L;
    static final long B_ROUTE = 8621L;
    static final long A_HOME = 8631L;
    static final long B_HOME = 8632L;
    static final long B_GROUP = 8633L;
    static final long A_CONFIG = 8641L;
    static final long B_CONFIG = 8642L;
    static final long B_SECRET = 8643L;
    static final long A_PIPELINE = 8651L;
    static final long B_PIPELINE = 8652L;
    static final long PLATFORM_PIPELINE = 8655L;
    static final String A_PIPELINE_ID = "ACME-PIPE-1";
    static final String B_PIPELINE_ID = "BRAVO-PIPE-77";
    static final String PLATFORM_PIPELINE_ID = "PLATFORM-PIPE-0";
    static final long A_TASK = 8661L;
    static final long B_TASK = 8662L;
    static final long A_JOB = 8671L;
    static final long COLLEAGUE_JOB = 8672L;
    static final long B_JOB = 8673L;
    /**
     * A job of B's whose assignee is A's own user. The API cannot make one (validateAssignee), but nothing in the
     * schema forbids it -- no foreign key ties an assignee's workspace to the job's -- so a read that trusts the
     * assignee alone, without the tenant, would hand B's job to A. The notifications probe asks the same question of
     * the bell with the same person id in B.
     */
    static final long B_JOB_NAMING_USER_A = 8674L;
    static final long A_RUN = 8681L;
    static final long COLLEAGUE_RUN = 8682L;
    static final long B_RUN = 8683L;
    static final long B_RUN_NAMING_USER_A = 8684L;
    static final long A_AGENT = 8691L;
    static final long B_AGENT = 8692L;

    static final String A_BUCKET = "acme-files";
    static final String B_BUCKET = "bravo-exports";
    static final String CONFIG_BUCKET = "etl-config";
    static final String B_STORAGE_ALIAS = "bravo-certs";
    static final String A_STORAGE_ALIAS = "acme-certs";
    /** Kafka key material a tenant user of B uploaded: kafka-secrets/{appUserId}/{uploadId}/{day}/{file}. */
    static final String B_SECRET_KEY = "kafka-secrets/" + USER_B + "/bravo01/2026-09-20/ca.pem";
    static final String B_PRIVATE_KEY = "kafka-secrets/" + USER_B + "/bravo01/2026-09-20/client.key";

    /** Every run sits in the last full Chicago hour, so a date range, a heatmap cell and a 7-day window all find it. */
    static final ZonedDateTime RUN_AT = ZonedDateTime.now(ZoneId.of("America/Chicago")).truncatedTo(ChronoUnit.HOURS).minusHours(1);
    static final String DAY = RUN_AT.toLocalDate().toString();
    static final String DAY_BEFORE = RUN_AT.toLocalDate().minusDays(1).toString();
    static final String DAY_AFTER = RUN_AT.toLocalDate().plusDays(1).toString();
    static final long HOUR = RUN_AT.getHour();

    // ---- markers ---------------------------------------------------------------------------------------------

    /** What of B's must never reach anyone outside B: its name, its people, and every row's own content. */
    static final List<String> B_MARKERS = Arrays.asList("Bravo Hidden Workspace", "bravo.example", "Bella Bravo", "Brian Bravo",
        "Bravo Kafka Secret", "bravo-broker.example", "BRAVO-SEALED", "bravo/truststore.p12", "bravo cluster said hello",
        "Bravo Secret Topic", "bravo-secret-topic", "Bravo Secret Home", "bravo-intranet.example", "Bravo Secret Group",
        "bravo-config-value", "Bravo Secret Pipeline", "Bravo secret field", "bravo_tag", "Bravo Secret Task",
        "bravo payload marker", "Bravo Secret Job", "Bravo Job Naming Adam", "bravo run message", "bravo audit line",
        "bravo stored object");

    /** A's, for a caller of another workspace or of none. */
    static final List<String> A_MARKERS = Arrays.asList("Acme Workspace", "Alice Acme", "Adam Acme", "Acme Kafka",
        "acme-broker.internal", "Acme Topic", "acme-jobs-topic", "Acme Home", "acme-config-value", "Acme Pipeline",
        "Acme Task", "Acme Own Job", "acme run message");

    /** C's, likewise. */
    static final List<String> C_MARKERS = Arrays.asList("Charlie Workspace", "Cora Charlie", "Charlie Topic");

    /** A colleague's job in A: a tenant user sees and acts on their own jobs only (JobOwnership). */
    static final List<String> COLLEAGUE_MARKERS = Arrays.asList("Colleague Private Job", "colleague run message",
        "colleague audit line", "Carl Colleague");

    /**
     * The platform default's connection details. A workspace with none of its own is shown that the default exists
     * (read-only) and may route through it, but never where its brokers are or how it logs in (getProfileDto).
     */
    static final List<String> PLATFORM_SECRET_MARKERS = Arrays.asList("platform-broker.internal", "platform-svc",
        "PLATFORM-SEALED", "platform/truststore.p12", "platform cluster said hello");

    /** The platform's rows by name: what a caller with no workspace must not be shown either. */
    static final List<String> PLATFORM_MARKERS = Arrays.asList("Platform Default Kafka", "Platform Legacy Pipeline",
        "Root Admin");

    // ---- callers ---------------------------------------------------------------------------------------------

    /** Who a probe call is made as: what the JWT filter puts on TenantContext, and the bearer token storage sees. */
    static final class Caller {
        final Long tenantId;
        final String role;
        final Long appUserId;
        final String username;

        Caller(Long tenantId, String role, Long appUserId, String username) {
            this.tenantId = tenantId;
            this.role = role;
            this.appUserId = appUserId;
            this.username = username;
        }

        String bearer() {
            return "Bearer caller-" + this.appUserId;
        }

        boolean isPlatformAdmin() {
            return "PLATFORM_ADMIN".equals(this.role);
        }

        @Override
        public String toString() {
            return this.role + "(" + this.appUserId + "@" + this.tenantId + ")";
        }
    }

    static final Caller ADMIN_OF_A = new Caller(A, "TENANT_ADMIN", ADMIN_A, "alice@acme.example");
    static final Caller USER_OF_A = new Caller(A, "TENANT_USER", USER_A, "adam@acme.example");
    static final Caller ADMIN_OF_C = new Caller(C, "TENANT_ADMIN", ADMIN_C, "cora@charlie.example");
    static final Caller PLATFORM = new Caller(null, "PLATFORM_ADMIN", ROOT, "root@platform.example");

    /**
     * Every way a token can name no workspace: none at all, and a tenant id that is not a real one. Tenant ids start
     * at 1000; 0 and -1 name no row, and a path that took them at face value would file a row under them, or -- where
     * 0 was once read as "every workspace" -- list everyone's. TenantContext.scope() already reads both as nothing;
     * every path that reads the tenant id directly has to as well.
     */
    static final Long[] NO_WORKSPACE = {null, 0L, -1L};

    static Caller tenantlessAdmin(Long tenantId) {
        return new Caller(tenantId, "TENANT_ADMIN", ORPHAN, "orphan@nowhere.example");
    }

    static Caller tenantlessUser(Long tenantId) {
        return new Caller(tenantId, "TENANT_USER", ORPHAN, "orphan@nowhere.example");
    }

    /** One endpoint call: a controller method, which may declare a checked exception (DashboardRestApi does). */
    interface Endpoint {
        ResponseEntity<?> call() throws Exception;
    }

    /** Serialised as the application's Jackson would: Java time as text, Optional unwrapped. */
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    // ---- the world -------------------------------------------------------------------------------------------

    final ScratchPostgres db;
    final ScratchJpa jpa;
    final FakeStorage storage;
    final List<String> leaks = new ArrayList<>();

    final IdentityPort identity;
    final ProducerBulkEngine engine = mock(ProducerBulkEngine.class);
    final BulkAction bulkAction = mock(BulkAction.class);
    final NotificationPort notifications = mock(NotificationPort.class);
    final JobMail jobMail = mock(JobMail.class);
    final OpenSearchAuditLogClient openSearch = mock(OpenSearchAuditLogClient.class);
    final AiAgentService agents = mock(AiAgentService.class);
    final MediaPort media = mock(MediaPort.class);
    final KafkaTemplateProvider kafkaClients = mock(KafkaTemplateProvider.class);
    final EncryptionUtil encryption = mock(EncryptionUtil.class);
    final TrustedStorageOperations trustedStorage = mock(TrustedStorageOperations.class);
    final RemoteStorageDirectory storageDirectory = mock(RemoteStorageDirectory.class);
    final OpenSearchRagClient rag = mock(OpenSearchRagClient.class);
    final EmbeddingService embeddings = mock(EmbeddingService.class);
    final FileIndexLock indexLock = mock(FileIndexLock.class);
    /** ai-service, as Core's AI port: each probe class says what it answers (AI decides; Core obeys). */
    final AiPort ai = mock(AiPort.class);

    final SourceJobRestApi sourceJobs;
    final SourceTaskRestApi sourceTasks;
    final PipelineRestApi pipelines;
    /** MIG-230: a pipeline's definition as ordered steps. */
    final PipelineDefinitionRestApi pipelineSteps;
    /** MIG-230: a run's steps, for the timeline. */
    final StepTimelineRestApi stepTimeline;
    /** MIG-237: a run's two-party result review, the console's half. */
    final RunReviewRestApi runReview;
    /** Where the run datasets the probes download live (Wave 4): a scratch directory, as FileDatasetStore keeps them. */
    final FileDatasetStore runDatasets;
    final KafkaConnectionProfileRestApi kafkaProfiles;
    final KafkaSecretRestApi kafkaSecrets;
    final SettingRestApi settings;
    final PipelineConfigRestApi pipelineConfig;
    final TaskReferenceRestApi taskReferences;
    final DashboardRestApi dashboard;
    final MessageQRestApi messages;
    final ReportRestApi reports;
    final FileChatRestApi fileChat;
    final AiModelChoiceRestApi modelChoice;
    final InboxTriggerRestApi inboxTriggers;

    private CoreProbeFixture(String prefix) throws Exception {
        this.db = ScratchPostgres.create(prefix);
        // The application's own pool since V181 (MIG-258): SET ROLE process_app, the caller on every connection. Every
        // probe runs under row-level security as well as the code's own scoping; the seed is the login's.
        this.jpa = new ScratchJpa(this.db.appPool());
        seed(this.db.jdbc());
        this.storage = new FakeStorage();

        this.identity = TestIdentity.over(this.jpa.repository(AppUserRepository.class), this.jpa.repository(TenantRepository.class));
        UserNameResolver names = new UserNameResolver(this.identity);
        TenantFilterHelper filters = new TenantFilterHelper();
        SourceJobRepository jobRows = this.jpa.repository(SourceJobRepository.class);
        SourceTaskRepository taskRows = this.jpa.repository(SourceTaskRepository.class);
        SourceTaskTypeRepository typeRows = this.jpa.repository(SourceTaskTypeRepository.class);
        SchedulerRepository schedulerRows = this.jpa.repository(SchedulerRepository.class);
        JobQueueRepository runRows = this.jpa.repository(JobQueueRepository.class);
        JobAuditLogRepository auditRows = this.jpa.repository(JobAuditLogRepository.class);
        TaskReferenceRepository referenceRows = this.jpa.repository(TaskReferenceRepository.class);
        KafkaConnectionProfileRepository profileRows = this.jpa.repository(KafkaConnectionProfileRepository.class);
        TenantTaskTypeKafkaRouteRepository routeRows = this.jpa.repository(TenantTaskTypeKafkaRouteRepository.class);
        PipelineRepository pipelineRows = this.jpa.repository(PipelineRepository.class);
        PipelineConfigRepository configRows = this.jpa.repository(PipelineConfigRepository.class);

        when(this.encryption.hasCurrentKey()).thenReturn(true);
        when(this.encryption.encrypt(any())).thenAnswer(call -> "k1:" + call.getArgument(0));
        when(this.agents.resolveRuntimeConfig(anyLong())).thenAnswer(call -> this.agentConfig(call.getArgument(0)));
        when(this.agents.processAdHoc(any())).thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "answered", "an answer"));
        when(this.storageDirectory.byAlias(eq(B_STORAGE_ALIAS))).thenReturn(Collections.singletonList(connection(B, B_STORAGE_ALIAS)));
        when(this.storageDirectory.byAlias(eq(A_STORAGE_ALIAS))).thenReturn(Collections.singletonList(connection(A, A_STORAGE_ALIAS)));

        QueryService queries = new QueryService();
        ReflectionTestUtils.setField(queries, "_em", this.jpa.sharedEntityManager());
        TransactionServiceImpl transactions = new TransactionServiceImpl(jobRows, schedulerRows, runRows, referenceRows, auditRows,
            taskRows, this.openSearch);
        KafkaConnectionResolver resolver = new KafkaConnectionResolver(routeRows, typeRows, profileRows);

        SourceJobServiceImpl jobs = new SourceJobServiceImpl(jobRows, schedulerRows, taskRows, auditRows, runRows, referenceRows,
            this.identity, this.engine, filters, this.openSearch, this.notifications, names);
        ReflectionTestUtils.setField(jobs, "entityManager", this.jpa.sharedEntityManager());
        JobAssistantServiceImpl assistant = new JobAssistantServiceImpl(jobRows, runRows, schedulerRows, this.agents, filters);
        ReflectionTestUtils.setField(assistant, "entityManager", this.jpa.sharedEntityManager());
        SourceJobBulkServiceImpl bulk = new SourceJobBulkServiceImpl(transactions, jobRows, schedulerRows, new BulkExcel(),
            this.notifications);
        this.sourceJobs = new SourceJobRestApi(jobs, bulk, assistant);

        SourceTaskServiceImpl tasks = new SourceTaskServiceImpl(new BulkExcel(), queries, jobRows, taskRows, typeRows, filters,
            new TaskPayloadLocationUtil(), this.identity, this.notifications, names);
        ReflectionTestUtils.setField(tasks, "entityManager", this.jpa.sharedEntityManager());
        // Wired as the application wires them: without these the task service falls back to weaker checks.
        ReflectionTestUtils.setField(tasks, "taskReferenceRepository", referenceRows);
        ReflectionTestUtils.setField(tasks, "taskConfigRules", new TaskConfigRules(configRows));
        this.sourceTasks = new SourceTaskRestApi(tasks);

        this.pipelines = new PipelineRestApi(new PipelineServiceImpl(pipelineRows, this.identity, names, typeRows));
        StepTasks stepTasks = Definitions.builtInTasks();
        JdbcTaskOverrideStore taskSwitches = new JdbcTaskOverrideStore(this.db.appJdbc());
        TaskRegistry registry = new TaskRegistry(stepTasks, taskSwitches);
        this.pipelineSteps = new PipelineDefinitionRestApi(new PipelineDefinitionService(pipelineRows,
            new PipelineDefinitionStore(this.db.appJdbc()), new DefinitionValidator(registry), registry, taskSwitches));
        this.runDatasets = new FileDatasetStore(Files.createTempDirectory("core-probe-datasets").toString());
        JdbcRunReviewStore reviewRows = new JdbcRunReviewStore(this.db.appJdbc());
        RunReviews runReviews = new RunReviews(new JdbcStepStore(this.db.appJdbc()), new PipelineDefinitionStore(this.db.appJdbc()),
            reviewRows);
        this.stepTimeline = new StepTimelineRestApi(new StepTimelineService(runRows, jobRows, new JdbcStepStore(this.db.appJdbc()),
            new JdbcModelChoiceStore(this.db.appJdbc()), this.runDatasets, runReviews), new FileAccessLog(this.db.appJdbc()));
        this.runReview = new RunReviewRestApi(new RunReviewService(runRows, jobRows, runReviews, reviewRows, jobs, transactions));

        KafkaSecretServiceImpl secrets = new KafkaSecretServiceImpl(this.trustedStorage, this.identity, this.encryption, CONFIG_BUCKET);
        ReflectionTestUtils.setField(secrets, "maxFileSizeKb", 512);
        this.kafkaSecrets = new KafkaSecretRestApi(secrets);
        this.kafkaProfiles = new KafkaConnectionProfileRestApi(new KafkaConnectionProfileServiceImpl(profileRows, typeRows, routeRows,
            this.encryption, this.kafkaClients, resolver, names, secrets, this.storageDirectory));

        SettingServiceImpl settingService = new SettingServiceImpl(jobRows, typeRows, profileRows, routeRows, this.identity,
            this.kafkaClients, resolver, names);
        ReflectionTestUtils.setField(settingService, "pipelineRepository", pipelineRows);
        this.settings = new SettingRestApi(settingService, new XmlOutTagInfoUtil());
        this.pipelineConfig = new PipelineConfigRestApi(new PipelineConfigService(configRows, taskRows, this.encryption, this.identity,
            names));
        this.taskReferences = new TaskReferenceRestApi(new TaskReferenceService(referenceRows, taskRows, this.identity, names));

        this.dashboard = new DashboardRestApi(new DashboardServiceImpl(queries, jobRows, runRows, schedulerRows, referenceRows,
            this.identity));
        this.messages = new MessageQRestApi(new MessageQServiceImpl(this.bulkAction, queries, runRows, jobRows, this.jobMail));
        HttpStorageBrowser asTheCaller = new HttpStorageBrowser(new StorageServiceClient(this.storage.url(), "service-token"));
        this.reports = new ReportRestApi(new ReportExportServiceImpl(this.media, asTheCaller, queries, this.identity));
        this.fileChat = new FileChatRestApi(new FileChatServiceImpl(asTheCaller, this.media, this.agents, this.rag, this.embeddings,
            this.indexLock));
        this.modelChoice = new AiModelChoiceRestApi(new AiModelChoiceService(jobRows, taskRows, runRows, pipelineRows, this.ai,
            new JdbcModelChoiceStore(this.db.appJdbc()), jobs));
        this.inboxTriggers = new InboxTriggerRestApi(new InboxTriggerService(new JdbcInboxTriggerStore(this.db.appJdbc()), this.engine,
            transactions, this.jpa.transactionManager()));
    }

    /** Skips the calling class when no database is configured, as every ScratchPostgres test does. */
    static CoreProbeFixture create(String prefix) throws Exception {
        return new CoreProbeFixture(prefix);
    }

    /** What the AI service answers for an agent: A's resolves, anyone else's is not found (AI decides; Core obeys). */
    private ResponseDto agentConfig(Long aiAgentId) {
        if (aiAgentId == null || aiAgentId != A_AGENT) {
            return new ResponseDto(ProcessUtil.ERROR, "AI agent not found.");
        }
        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider("ollama");
        config.setModel("acme-model");
        config.setInstructions("be brief");
        return new ResponseDto(ProcessUtil.SUCCESS, "Agent resolved.", config);
    }

    private static StorageConnection connection(long tenantId, String alias) {
        StorageConnection connection = new StorageConnection();
        connection.setTenantId(tenantId);
        connection.setAlias(alias);
        connection.setConnectionName(alias);
        return connection;
    }

    // ---- seeding ---------------------------------------------------------------------------------------------

    private static void seed(JdbcTemplate sql) {
        workspace(sql, A, "acme", "Acme Workspace", "Active");
        workspace(sql, B, "bravo", "Bravo Hidden Workspace", "Active");
        workspace(sql, C, "charlie", "Charlie Workspace", "Active");
        workspace(sql, GONE, "gone", "Gone Workspace", "Delete");
        workspace(sql, DEFAULT_WS, "default", "Default Workspace", "Active");

        person(sql, ROOT, null, "PLATFORM_ADMIN", "root@platform.example", "Root Admin");
        person(sql, ADMIN_A, A, "TENANT_ADMIN", "alice@acme.example", "Alice Acme");
        person(sql, USER_A, A, "TENANT_USER", "adam@acme.example", "Adam Acme");
        person(sql, COLLEAGUE_A, A, "TENANT_USER", "carl@acme.example", "Carl Colleague");
        person(sql, ADMIN_B, B, "TENANT_ADMIN", "brian@bravo.example", "Brian Bravo");
        person(sql, USER_B, B, "TENANT_USER", "bella@bravo.example", "Bella Bravo");
        person(sql, ADMIN_C, C, "TENANT_ADMIN", "cora@charlie.example", "Cora Charlie");

        // Kafka: the platform default (tenant_id NULL, kept on purpose), A's and B's own defaults; C has none. The
        // changelog seeds a platform default of its own; it is demoted so this one, with its markers, is the default.
        sql.update("UPDATE kafka_connection_profile SET is_default = false WHERE tenant_id IS NULL");
        sql.update("INSERT INTO kafka_connection_profile (kafka_connection_profile_id, tenant_id, profile_name, bootstrap_servers, "
            + "security_protocol, sasl_mechanism, sasl_username, sasl_password, ssl_truststore_bucket, ssl_truststore_location, "
            + "last_test_message, is_default, status) VALUES "
            + "(?, NULL, 'Platform Default Kafka', 'platform-broker.internal:9092', 'SASL_SSL', 'PLAIN', 'platform-svc', "
            + "'PLATFORM-SEALED-SASL', 'etl-config', 'platform/truststore.p12', 'platform cluster said hello', true, 'Active'), "
            + "(?, ?, 'Acme Kafka', 'acme-broker.internal:9092', 'PLAINTEXT', NULL, NULL, NULL, NULL, NULL, NULL, true, 'Active'), "
            + "(?, ?, 'Bravo Kafka Secret', 'bravo-broker.example:9093', 'SASL_SSL', 'PLAIN', 'bravo-svc', 'BRAVO-SEALED-SASL', "
            + "'bravo-certs', 'bravo/truststore.p12', 'bravo cluster said hello', true, 'Active')",
            PLATFORM_PROFILE, A_PROFILE, A, B_PROFILE, B);

        topic(sql, A_TYPE, A, "Acme Topic", "acme-jobs-topic", A_PROFILE);
        topic(sql, B_TYPE, B, "Bravo Secret Topic", "bravo-secret-topic", B_PROFILE);
        topic(sql, C_TYPE, C, "Charlie Topic", "charlie-topic", null);
        topic(sql, DEFAULT_TYPE, DEFAULT_WS, "Default Topic", "default-topic", null);
        sql.update("INSERT INTO tenant_task_type_kafka_route (tenant_task_type_kafka_route_id, tenant_id, source_task_type_id, "
            + "kafka_connection_profile_id, date_created) VALUES (?, ?, ?, ?, now())", B_ROUTE, B, B_TYPE, B_PROFILE);

        sql.update("INSERT INTO task_reference (id, tenant_id, kind, name, value) VALUES "
            + "(?, ?, 'HOME_PAGE', 'Acme Home', 'https://acme.example/home'), "
            + "(?, ?, 'HOME_PAGE', 'Bravo Secret Home', 'https://bravo-intranet.example/secret'), "
            + "(?, ?, 'TASK_GROUP', 'Bravo Secret Group', NULL)", A_HOME, A, B_HOME, B, B_GROUP, B);
        sql.update("INSERT INTO pipeline_config (id, tenant_id, config_key, kind, value, value_sealed) VALUES "
            + "(?, ?, 'ACME_KEY', 'VALUE', 'acme-config-value', NULL), "
            + "(?, ?, 'BRAVO_KEY', 'VALUE', 'bravo-config-value-marker', NULL), "
            + "(?, ?, 'BRAVO_SECRET', 'SECRET', NULL, 'kbravo:BRAVO-SEALED-SECRET')", A_CONFIG, A, B_CONFIG, B, B_SECRET, B);

        pipeline(sql, A_PIPELINE, A, A_PIPELINE_ID, "Acme Pipeline", A_TYPE, "acme_tag", "Acme field");
        pipeline(sql, B_PIPELINE, B, B_PIPELINE_ID, "Bravo Secret Pipeline", B_TYPE, "bravo_tag", "Bravo secret field");
        // A legacy row with no workspace. V69.0 backfilled most; the column is still nullable, so a read that lets a
        // caller with no workspace match "tenant_id IS NULL" would hand this out.
        pipeline(sql, PLATFORM_PIPELINE, null, PLATFORM_PIPELINE_ID, "Platform Legacy Pipeline", null, "platform_tag", "Platform field");

        task(sql, A_TASK, A, A_TYPE, "Acme Task", "<task><bucket>acme-files</bucket></task>", A_HOME, null);
        task(sql, B_TASK, B, B_TYPE, "Bravo Secret Task", "<task><bucket>bravo-exports</bucket><note>bravo payload marker</note></task>",
            B_HOME, B_GROUP);
        sql.update("INSERT INTO source_task_payload (task_payload_id, tag_key, tag_value, payload_id, tenant_id) VALUES "
            + "(?, 'bucket', 'acme-files', ?, ?), (?, 'note', 'bravo payload marker', ?, ?)", 8665L, A_TASK, A, 8666L, B_TASK, B);

        job(sql, A_JOB, A, A_TASK, "Acme Own Job", USER_A, USER_A);
        job(sql, COLLEAGUE_JOB, A, A_TASK, "Colleague Private Job", COLLEAGUE_A, COLLEAGUE_A);
        job(sql, B_JOB, B, B_TASK, "Bravo Secret Job", USER_B, USER_B);
        job(sql, B_JOB_NAMING_USER_A, B, B_TASK, "Bravo Job Naming Adam", USER_B, USER_A);
        run(sql, A_RUN, A_JOB, A, "acme run message");
        run(sql, COLLEAGUE_RUN, COLLEAGUE_JOB, A, "colleague run message");
        run(sql, B_RUN, B_JOB, B, "bravo run message marker");
        run(sql, B_RUN_NAMING_USER_A, B_JOB_NAMING_USER_A, B, "bravo run message for adam");
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status, tenant_id) VALUES "
            + "(?, ?, ?, 'colleague audit line', 'Active', ?), (?, ?, ?, 'bravo audit line', 'Active', ?)",
            COLLEAGUE_RUN + 100, runAt(), COLLEAGUE_RUN, A, B_RUN + 100, runAt(), B_RUN, B);
    }

    private static Timestamp runAt() {
        return Timestamp.from(RUN_AT.toInstant());
    }

    private static void workspace(JdbcTemplate sql, long id, String code, String name, String status) {
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, ?, ?, ?)", id, status, code, name);
    }

    private static void person(JdbcTemplate sql, long id, Long tenant, String role, String username, String name) {
        sql.update("INSERT INTO app_user (app_user_id, tenant_id, full_name, password, status, user_role, username) "
            + "VALUES (?, ?, ?, 'hash', 'Active', ?, ?)", id, tenant, name, role, username);
    }

    private static void topic(JdbcTemplate sql, long id, long tenant, String name, String kafkaTopic, Long profile) {
        sql.update("INSERT INTO source_task_type (source_task_type_id, tenant_id, service_name, description, queue_topic_partition, "
            + "task_type_status, kafka_connection_profile_id) VALUES (?, ?, ?, ?, ?, 'Active', ?)",
            id, tenant, name, name + " description", "topic=" + kafkaTopic + "&partitions=[*]", profile);
    }

    private static void pipeline(JdbcTemplate sql, long key, Long tenant, String pipelineId, String name, Long topic, String tag,
        String label) {
        sql.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status, source_task_type_id) "
            + "VALUES (?, ?, ?, ?, 'Active', ?)", key, tenant, pipelineId, name, topic);
        if (tenant != null) {
            sql.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label, field_type, tenant_id) "
                + "VALUES (?, ?, ?, ?, 'text', ?)", key + 1000, key, tag, label, tenant);
        }
    }

    private static void task(JdbcTemplate sql, long id, long tenant, long type, String name, String payload, Long home, Long group) {
        sql.update("INSERT INTO source_task (task_detail_id, tenant_id, source_task_type_id, task_name, task_payload, task_status, "
            + "pipeline_id, home_page_id, group_id, date_created) VALUES (?, ?, ?, ?, ?, 'Active', ?, ?, ?, now())",
            id, tenant, type, name, payload, tenant == A ? A_PIPELINE_ID : B_PIPELINE_ID, home, group);
    }

    private static void job(JdbcTemplate sql, long id, long tenant, long task, String name, long createdBy, long assignedTo) {
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, job_running_status, priority, "
            + "tenant_id, task_detail_id, created_by, assigned_user_id, complete_job, fail_job, skip_job) "
            + "VALUES (?, ?, 'Auto', ?, 'Active', 'Completed', 1, ?, ?, ?, ?, false, true, false)",
            id, runAt(), name, tenant, task, createdBy, assignedTo);
        sql.update("INSERT INTO scheduler (scheduler_id, frequency, interval_value, job_id, start_date, start_time, next_run_at, tenant_id) "
            + "VALUES (?, 'Daily', '1', ?, current_date - 1, '00:00', now() + interval '1 day', ?)", id + 100, id, tenant);
    }

    private static void run(JdbcTemplate sql, long id, long job, long tenant, String message) {
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, start_time, end_time, job_id, job_status, job_status_message, "
            + "status, tenant_id, job_send) VALUES (?, ?, ?, ?, ?, 'Running', ?, 'Active', ?, true)",
            id, runAt(), runAt(), Timestamp.from(RUN_AT.plusMinutes(5).toInstant()), job, message, tenant);
    }

    // ---- probing ---------------------------------------------------------------------------------------------

    /**
     * One endpoint call as the caller, the way a request makes it: TenantContext as JwtAuthenticationFilter sets it,
     * the caller's bearer token on the current request (storage-service is reached as the signed-in user), and one
     * transaction around the call, as @Transactional gives the services in the application -- there is no proxy here.
     * The answer is serialised inside that transaction and searched for every marker the caller must not see.
     */
    String probe(String endpoint, Caller caller, Endpoint call) {
        TenantContext.set(caller.tenantId, caller.role, caller.appUserId, caller.username);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", caller.bearer());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        String body;
        try {
            body = this.jpa.transactions().execute(status -> {
                try {
                    return render(call.call());
                } catch (Exception thrown) {
                    // What escaped the controller: GlobalExceptionHandler answers it in the application. Its
                    // message is searched like any answer, since the handler may repeat it.
                    status.setRollbackOnly();
                    return "threw " + thrown.getClass().getSimpleName() + ": " + thrown.getMessage();
                }
            });
        } catch (RuntimeException atCommit) {
            // The controller answered, and the write it attempted failed at commit and was rolled back -- a NOT NULL
            // or a check constraint, say. Nothing was written; the answer is recorded as what it was.
            body = "rolled back at commit: " + atCommit.getClass().getSimpleName() + ": " + atCommit.getMessage();
        } finally {
            TenantContext.clear();
            RequestContextHolder.resetRequestAttributes();
        }
        for (String marker : this.foreignMarkers(caller)) {
            if (body.contains(marker)) {
                this.leaks.add(endpoint + " as " + caller + " returned \"" + marker + "\": " + abbreviate(body));
            }
        }
        return body;
    }

    /**
     * Before each test: no leaks recorded, no calls remembered by the collaborators, no requests by storage -- so a
     * test's "never called with B's" is about that test's calls alone, whatever order JUnit runs them in.
     */
    void reset() {
        this.leaks.clear();
        clearInvocations(this.engine, this.bulkAction, this.notifications, this.jobMail, this.openSearch, this.agents, this.media,
            this.kafkaClients, this.trustedStorage, this.storageDirectory, this.rag, this.embeddings, this.indexLock, this.ai);
        this.storage.seen.clear();
        this.storage.stored.clear();
    }

    /** Everything that is not the caller's: every other workspace's, the platform's secrets, and a colleague's jobs. */
    private List<String> foreignMarkers(Caller caller) {
        List<String> foreign = new ArrayList<>(PLATFORM_SECRET_MARKERS);
        if (caller.isPlatformAdmin()) {
            return Collections.emptyList();
        }
        if (caller.tenantId == null || caller.tenantId <= 0) {
            foreign.addAll(PLATFORM_MARKERS);
        }
        if (!Long.valueOf(B).equals(caller.tenantId)) {
            foreign.addAll(B_MARKERS);
        }
        if (!Long.valueOf(A).equals(caller.tenantId)) {
            foreign.addAll(A_MARKERS);
            foreign.addAll(COLLEAGUE_MARKERS);
        } else if (!"TENANT_ADMIN".equals(caller.role)) {
            foreign.addAll(COLLEAGUE_MARKERS);
        }
        if (!Long.valueOf(C).equals(caller.tenantId)) {
            foreign.addAll(C_MARKERS);
        }
        return foreign;
    }

    private static String render(ResponseEntity<?> answer) throws IOException {
        Object body = answer == null ? null : answer.getBody();
        if (body == null) {
            return "";
        }
        if (body instanceof byte[]) {
            return workbookText((byte[]) body);
        }
        if (body instanceof StreamingResponseBody) {
            // A streamed download (Wave 4): what the caller would receive.
            ByteArrayOutputStream streamed = new ByteArrayOutputStream();
            ((StreamingResponseBody) body).writeTo(streamed);
            return "HTTP " + answer.getStatusCodeValue() + " " + answer.getHeaders().getFirst("Content-Disposition") + "\n"
                + new String(streamed.toByteArray(), StandardCharsets.UTF_8);
        }
        return JSON.writeValueAsString(body);
    }

    /** Every XML part of a workbook, as text: shared strings, sheets, and the data-validation lists alike. */
    static String workbookText(byte[] bytes) throws IOException {
        StringBuilder text = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().endsWith(".xml")) {
                    text.append(entry.getName()).append(": ").append(new String(drain(zip), StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return text.length() == 0 ? new String(bytes, StandardCharsets.UTF_8) : text.toString();
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    private static String abbreviate(String body) {
        return body.length() > 600 ? body.substring(0, 600) + "..." : body;
    }

    /** How a refusal reads once serialised: a ResponseDto with status ERROR and its sentence. */
    static final String REFUSED = "\"status\":\"ERROR\"";

    static final String SUCCEEDED = "\"status\":\"SUCCESS\"";

    // ---- what must not change --------------------------------------------------------------------------------

    /**
     * Every row that is not A's, as one string: B's and the other workspaces' rows in every table that has a
     * tenant_id, the platform's rows (tenant_id NULL), and the colleague's job with its schedule, runs and audit
     * lines. Read from information_schema, so a table added later is covered without anyone remembering it.
     * Ordered by the whole row, which is stable where a primary key would not be (composite keys).
     */
    String foreignRows(long... workspaces) {
        JdbcTemplate sql = this.db.jdbc();
        StringBuilder rows = new StringBuilder();
        StringBuilder in = new StringBuilder();
        for (long workspace : workspaces) {
            in.append(in.length() == 0 ? "" : ", ").append(workspace);
        }
        for (String table : this.tenantTables()) {
            rows.append(table).append(": ").append(render(sql.queryForList("SELECT * FROM " + table + " t WHERE t.tenant_id IS NULL "
                + "OR t.tenant_id IN (" + in + ") ORDER BY t::text"))).append('\n');
        }
        rows.append("colleague's job: ")
            .append(render(sql.queryForList("SELECT * FROM source_job t WHERE job_id = ? ORDER BY t::text", COLLEAGUE_JOB)))
            .append(render(sql.queryForList("SELECT * FROM scheduler t WHERE job_id = ? ORDER BY t::text", COLLEAGUE_JOB)))
            .append(render(sql.queryForList("SELECT * FROM job_queue t WHERE job_id = ? ORDER BY t::text", COLLEAGUE_JOB)))
            .append(render(sql.queryForList("SELECT * FROM job_audit_logs t WHERE job_queue_id = ? ORDER BY t::text", COLLEAGUE_RUN)));
        return rows.toString();
    }

    /**
     * The rows a probe must leave alone by default: B's, C's, the deleted, default and unknown workspaces', the ids
     * that name no workspace (0, -1), and the platform's.
     */
    String foreignRows() {
        return this.foreignRows(B, C, GONE, DEFAULT_WS, UNKNOWN, 0L, -1L);
    }

    List<String> tenantTables() {
        return this.db.jdbc().queryForList("SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t "
            + "ON t.table_schema = c.table_schema AND t.table_name = c.table_name WHERE c.table_schema = 'public' "
            + "AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE' ORDER BY 1", String.class);
    }

    /** How many rows, in any table with a tenant_id, name one of these workspaces -- the tenant rows themselves aside. */
    long rowsNaming(long... workspaces) {
        long total = 0;
        for (String table : this.tenantTables()) {
            if (table.equals("tenant")) {
                continue;
            }
            for (long workspace : workspaces) {
                total += this.db.jdbc().queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", Long.class, workspace);
            }
        }
        return total;
    }

    private static String render(List<Map<String, Object>> rows) {
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> row : rows) {
            Map<String, Object> shown = new LinkedHashMap<>();
            row.forEach((column, value) -> shown.put(column, value instanceof byte[]
                ? Base64.getEncoder().encodeToString((byte[]) value) : value));
            text.append(shown);
        }
        return text.toString();
    }

    // ---- uploads ---------------------------------------------------------------------------------------------

    /**
     * A Job-Add workbook, one valid row per task id: everything but the task passes the row validator, so a
     * refusal can only be the task's (tomorrow's start date, as the validator refuses one in the past).
     */
    static FileUploadDto jobSheet(Long tenantIdInBody, String jobName, long... taskIds) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet(ProcessUtil.JOB_ADD);
            Row header = sheet.createRow(0);
            for (int i = 0; i < ProcessUtil.HEADER_FILED_BATCH_FILE.length; i++) {
                header.createCell(i).setCellValue(ProcessUtil.HEADER_FILED_BATCH_FILE[i]);
            }
            String startDate = LocalDate.now().plusDays(1).toString();
            for (int r = 0; r < taskIds.length; r++) {
                Row row = sheet.createRow(r + 1);
                String[] cells = {jobName + " " + r, String.valueOf(taskIds[r]), startDate, "", "02:00", "Daily", "1", "1",
                    "False", "False", "False"};
                for (int i = 0; i < cells.length; i++) {
                    row.createCell(i).setCellValue(cells[i]);
                }
            }
            workbook.write(out);
            return upload("jobs.xlsx", out.toByteArray(), tenantIdInBody);
        }
    }

    /** A ListSourceTask workbook with one row naming this task type and home page. */
    static FileUploadDto taskSheet(Long tenantIdInBody, long taskTypeId, String taskName, String homePageId) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("ListSourceTask");
            String[] header = {"TaskTypeId", "Task Name", "Task Payload", "PipelineId", "HomePage"};
            Row first = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                first.createCell(i).setCellValue(header[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(String.valueOf(taskTypeId));
            row.createCell(1).setCellValue(taskName);
            row.createCell(2).setCellValue("<task><bucket>acme-files</bucket></task>");
            row.createCell(3).setCellValue(A_PIPELINE_ID);
            row.createCell(4).setCellValue(homePageId == null ? "" : homePageId);
            workbook.write(out);
            return upload("tasks.xlsx", out.toByteArray(), tenantIdInBody);
        }
    }

    private static FileUploadDto upload(String name, byte[] bytes, Long tenantIdInBody) {
        FileUploadDto dto = new FileUploadDto();
        dto.setFile(new MockMultipartFile("file", name, ProcessUtil.SHEET_NAME, bytes));
        dto.setTenantId(tenantIdInBody);
        return dto;
    }

    Long tenantOf(String sqlReturningTenant, Object... args) {
        List<Long> found = this.db.jdbc().queryForList(sqlReturningTenant, Long.class, args);
        return found.isEmpty() ? null : found.get(0);
    }

    long count(String sql, Object... args) {
        return this.db.jdbc().queryForObject(sql, Long.class, args);
    }

    @Override
    public void close() throws Exception {
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
        this.storage.close();
        this.jpa.close();
        this.db.close();
    }

    // ---- storage-service, as the signed-in user ----------------------------------------------------------------

    /**
     * storage-service for the probes: answers the three guarded calls file chat and report export make, for the
     * buckets the presented token's person may use, and refuses every other bucket the way storage does (404 and
     * a sentence). It remembers each request with its Authorization header, so a probe can show the call went out
     * as the caller -- not as the service, and not through the trusted path, which carries X-Internal-Token.
     */
    static final class FakeStorage implements AutoCloseable {

        static final class Seen {
            final String method;
            final String path;
            final String authorization;
            final String internalToken;
            final String bucket;

            Seen(String method, String path, String authorization, String internalToken, String bucket) {
                this.method = method;
                this.path = path;
                this.authorization = authorization;
                this.internalToken = internalToken;
                this.bucket = bucket;
            }

            @Override
            public String toString() {
                return this.method + " " + this.path + " bucket=" + this.bucket + " as " + this.authorization;
            }
        }

        private static final Pattern BUCKET_PART = Pattern.compile("name=\"bucket\"\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n([^\\r\\n]*)\\r\\n");
        private static final Pattern BUCKET_QUERY = Pattern.compile("(?:^|&)bucket=([^&]*)");

        final List<Seen> seen = new CopyOnWriteArrayList<>();
        /** "bucket as token" for every object storage accepted. */
        final List<String> stored = new CopyOnWriteArrayList<>();
        private final HttpServer server;

        FakeStorage() throws IOException {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::answer);
            this.server.start();
        }

        String url() {
            return "http://127.0.0.1:" + this.server.getAddress().getPort();
        }

        /** The buckets a person may use: A's people A's bucket, B's people B's, anyone else none. */
        private static List<String> bucketsOf(String authorization) {
            if (authorization == null) {
                return Collections.emptyList();
            }
            for (long person : new long[] {ADMIN_A, USER_A, COLLEAGUE_A}) {
                if (authorization.equals("Bearer caller-" + person)) {
                    return Collections.singletonList(A_BUCKET);
                }
            }
            for (long person : new long[] {ADMIN_B, USER_B}) {
                if (authorization.equals("Bearer caller-" + person)) {
                    return Collections.singletonList(B_BUCKET);
                }
            }
            return Collections.emptyList();
        }

        private void answer(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getRawQuery();
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = drain(exchange.getRequestBody());
            String bucket = null;
            if (path.endsWith("/uploadObject")) {
                Matcher part = BUCKET_PART.matcher(new String(body, StandardCharsets.ISO_8859_1));
                bucket = part.find() ? part.group(1) : null;
            } else if (query != null) {
                Matcher parameter = BUCKET_QUERY.matcher(query);
                bucket = parameter.find() ? URLDecoder.decode(parameter.group(1), "UTF-8") : null;
            }
            this.seen.add(new Seen(exchange.getRequestMethod(), path, authorization,
                exchange.getRequestHeaders().getFirst("X-Internal-Token"), bucket));
            List<String> mine = bucketsOf(authorization);
            if (path.endsWith("/storage.json/buckets")) {
                StringBuilder data = new StringBuilder();
                for (String own : mine) {
                    data.append(data.length() == 0 ? "" : ",").append("{\"bucket\":\"").append(own).append("\",\"label\":\"")
                        .append(own).append("\",\"provider\":\"MINIO\"}");
                }
                this.reply(exchange, 200, "{\"status\":\"SUCCESS\",\"message\":\"Buckets.\",\"data\":[" + data + "]}");
            } else if (bucket == null || !mine.contains(bucket)) {
                this.reply(exchange, 404, "{\"status\":\"ERROR\",\"message\":\"No bucket called " + bucket + ".\"}");
            } else if (path.endsWith("/storage.json/uploadObject")) {
                this.stored.add(bucket + " as " + authorization);
                this.reply(exchange, 200, "{\"status\":\"SUCCESS\",\"message\":\"Uploaded.\"}");
            } else if (path.endsWith("/storage.json/objectMetadata")) {
                this.reply(exchange, 200, "{\"status\":\"SUCCESS\",\"message\":\"Found.\",\"data\":{\"name\":\"report.csv\","
                    + "\"key\":\"report.csv\",\"etag\":\"etag-1\",\"size\":12}}");
            } else {
                this.reply(exchange, 404, "{\"status\":\"ERROR\",\"message\":\"Not here.\"}");
            }
        }

        private void reply(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        /** Every request that named this bucket. */
        List<Seen> naming(String bucket) {
            List<Seen> found = new ArrayList<>();
            for (Seen request : this.seen) {
                if (bucket.equals(request.bucket)) {
                    found.add(request);
                }
            }
            return found;
        }

        @Override
        public void close() {
            this.server.stop(0);
        }
    }
}
