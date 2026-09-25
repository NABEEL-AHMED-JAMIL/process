package process.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;
import process.ai.AiPort;
import process.api.MeterRestApi;
import process.api.PipelineRestApi;
import process.api.SourceJobRestApi;
import process.api.SourceTaskRestApi;
import process.engine.ProducerBulkEngine;
import process.identity.IdentityPort;
import process.identity.InternalRunVerificationRestApi;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.SourceJobBulkService;
import process.model.service.impl.JobAssistantServiceImpl;
import process.model.service.impl.PipelineServiceImpl;
import process.model.service.impl.QueryService;
import process.model.service.impl.SourceJobServiceImpl;
import process.model.service.impl.SourceTaskServiceImpl;
import process.notifications.NotificationPort;
import process.security.RunCallbackTokens;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.OpenSearchAuditLogClient;
import process.util.TaskPayloadLocationUtil;
import process.util.UserNameResolver;
import process.util.excel.BulkExcel;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MIG-261: the endpoints whose data sits on an entity graph with lazy associations, driven over MockMvc twice --
 * once with Boot's OpenEntityManagerInViewInterceptor in front of them (open-in-view on, as every profile ran
 * until now) and once without it (off, as every profile now runs) -- against a changelog-built database.
 *
 * Everything under the request is the application's own: the controllers, the services behind their
 * transactional proxies, the Spring Data repositories, Hibernate, the tenant filter, and a Jackson mapper set
 * as the profiles set Boot's. Only what leaves the process is mocked (Identity, notifications, OpenSearch, the
 * dispatch engine). Each endpoint must answer the same status and byte-identical JSON either way: a response
 * that only rendered because the request held a session open would fail here with open-in-view off.
 *
 * lazyEntityProbe is the control: a response that does carry a lazy association renders with the interceptor
 * and fails without it, so the comparison can see the difference it is looking for.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class OpenInViewOffWebPostgresTest {

    private static final long TENANT = 2905L;
    private static final long PROFILE = 7290L;
    private static final long TYPE = 7300L;
    private static final long TASK = 7301L;
    private static final long JOB = 7304L;
    private static final long SCHEDULER = 7305L;
    private static final long RUN = 7306L;
    private static final long PIPELINE = 7310L;

    private static ScratchEtlJob db;
    private static AnnotationConfigApplicationContext context;
    private static MockMvc openInView;
    private static MockMvc noSession;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories(basePackages = "process.model.repository")
    @Import({SourceJobServiceImpl.class, SourceTaskServiceImpl.class, PipelineServiceImpl.class, QueryService.class,
        SourceJobRestApi.class, SourceTaskRestApi.class, PipelineRestApi.class, MeterRestApi.class, LazyEntityProbe.class})
    static class Slice {

        @Bean
        DataSource dataSource() {
            return db.dataSource();
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("process.model.pojo");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Map<String, Object> properties = new HashMap<>();
            properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
            properties.put("hibernate.enable_lazy_load_no_trans", "false");
            properties.put("hibernate.hbm2ddl.auto", "none");
            properties.put("hibernate.physical_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            properties.put("hibernate.implicit_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy");
            factory.setJpaPropertyMap(properties);
            return factory;
        }

        @Bean
        JpaTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        TenantFilterHelper tenantFilterHelper() {
            return new TenantFilterHelper();
        }

        @Bean
        IdentityPort identityPort() {
            return mock(IdentityPort.class);
        }

        @Bean
        UserNameResolver userNameResolver(IdentityPort identity) {
            return new UserNameResolver(identity);
        }

        @Bean
        NotificationPort notificationPort() {
            return mock(NotificationPort.class);
        }

        @Bean
        ProducerBulkEngine producerBulkEngine() {
            return mock(ProducerBulkEngine.class);
        }

        @Bean
        OpenSearchAuditLogClient openSearchAuditLogClient() {
            return mock(OpenSearchAuditLogClient.class);
        }

        @Bean
        BulkExcel bulkExcel() {
            return mock(BulkExcel.class);
        }

        @Bean
        TaskPayloadLocationUtil taskPayloadLocationUtil() {
            return mock(TaskPayloadLocationUtil.class);
        }

        @Bean
        SourceJobBulkService sourceJobBulkService() {
            return mock(SourceJobBulkService.class);
        }

        @Bean
        JobAssistantServiceImpl jobAssistantService() {
            return mock(JobAssistantServiceImpl.class);
        }

        @Bean
        AiPort aiPort() {
            return mock(AiPort.class);
        }

        /** A run's token is the worker's business; here every token is its run's own. */
        @Bean
        RunCallbackTokens runCallbackTokens() {
            RunCallbackTokens tokens = mock(RunCallbackTokens.class);
            when(tokens.verify(any(), any(), any())).thenReturn(Optional.empty());
            when(tokens.verifyForReport(any(), any(), any())).thenReturn(Optional.empty());
            return tokens;
        }

        @Bean
        InternalRunVerificationRestApi internalRunVerificationRestApi(RunCallbackTokens tokens, JobQueueRepository runs,
            SourceJobRepository jobs, PipelineRepository pipelines) {
            return new InternalRunVerificationRestApi(tokens, runs, jobs, pipelines, "internal-token");
        }
    }

    /** The control: an entity handed to Jackson whole, lazy sourceJob and all. */
    @RestController
    static class LazyEntityProbe {

        private final JobQueueRepository runs;

        LazyEntityProbe(JobQueueRepository runs) {
            this.runs = runs;
        }

        @GetMapping("/probe/run")
        public JobQueue run(@RequestParam Long jobQueueId) {
            return this.runs.findById(jobQueueId).orElse(null);
        }
    }

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("osiv_off");
        db.dataSource().getHikariConfigMXBean().setMaximumPoolSize(4);
        seed(db.sql());
        context = new AnnotationConfigApplicationContext(Slice.class);
        EntityManagerFactory factory = context.getBean(EntityManagerFactory.class);
        OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
        interceptor.setEntityManagerFactory(factory);
        openInView = mvc().addInterceptors(new WebRequestHandlerInterceptorAdapter(interceptor)).build();
        noSession = mvc().build();
    }

    /** The controllers under test, with the JSON mapper the profiles give Boot's (fail-on-empty-beans=false). */
    private static StandaloneMockMvcBuilder mvc() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.FAIL_ON_EMPTY_BEANS, SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .build();
        return MockMvcBuilders.standaloneSetup(context.getBean(SourceJobRestApi.class), context.getBean(SourceTaskRestApi.class),
                context.getBean(PipelineRestApi.class), context.getBean(MeterRestApi.class),
                context.getBean(InternalRunVerificationRestApi.class), context.getBean(LazyEntityProbe.class))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper));
    }

    private static void seed(JdbcTemplate sql) {
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'MCN', 'MedAxis')", TENANT);
        sql.update("INSERT INTO kafka_connection_profile (kafka_connection_profile_id, tenant_id, profile_name, bootstrap_servers, "
            + "security_protocol, is_default, status, date_created) VALUES (?, ?, 'Workspace brokers', 'ws-kafka:9092', 'PLAINTEXT', "
            + "false, 'Active', now())", PROFILE, TENANT);
        sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id, "
            + "kafka_connection_profile_id) VALUES (?, 'worker', 'd', 'topic=scrapping-topic&partitions=[*]', ?, ?)", TYPE, TENANT, PROFILE);
        sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id, pipeline_id) "
            + "VALUES (?, 'claims task', 'Active', ?, ?, 'F-CLAIMS')", TASK, TYPE, TENANT);
        sql.update("INSERT INTO source_task_payload (task_payload_id, tag_key, tag_value, payload_id, tenant_id) VALUES (7302, 'bucket', 'b', ?, ?)",
            TASK, TENANT);
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, task_detail_id, "
            + "complete_job, fail_job, skip_job) VALUES (?, now(), 'Auto', 'claims', 'Active', 1, ?, ?, false, false, false)", JOB, TENANT, TASK);
        sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, expired, tenant_id) "
            + "VALUES (?, ?, '2026-01-01', '09:00', 'Daily', '1', false, ?)", SCHEDULER, JOB, TENANT);
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, job_send, tenant_id) "
            + "VALUES (?, '2026-09-20 10:00:00', ?, 'Completed', 'Active', true, ?)", RUN, JOB, TENANT);
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status, tenant_id) "
            + "VALUES (7307, '2026-09-20 10:00:01', ?, 'line', 'Active', ?)", RUN, TENANT);
        sql.update("INSERT INTO pipeline (pipeline_key, pipeline_id, pipeline_name, tenant_id, status, source_task_type_id, date_created) "
            + "VALUES (?, 'F-CLAIMS', 'Claims', ?, 'Active', ?, '2026-09-01 08:00:00')", PIPELINE, TENANT, TYPE);
        sql.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label, field_type, required, position, "
            + "prompt_id, run_in, tenant_id) VALUES (7311, ?, 'summary', 'Summary', 'ai', false, 1, 42, 'worker', ?)", PIPELINE, TENANT);
    }

    @AfterAll
    static void drop() throws Exception {
        if (context != null) {
            context.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void asTheWorkspaceAdmin() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 7308L, "admin");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Status and body with the session held open for the request, and without; they must be the same. */
    private static String sameEitherWay(RequestBuilder request) throws Exception {
        MvcResult before = openInView.perform(request).andReturn();
        MvcResult after = noSession.perform(request).andReturn();
        assertThat(after.getResponse().getStatus()).as("status with open-in-view off, against on")
            .isEqualTo(before.getResponse().getStatus()).isEqualTo(200);
        String body = after.getResponse().getContentAsString();
        assertThat(body).as("JSON with open-in-view off, against on").isEqualTo(before.getResponse().getContentAsString());
        return body;
    }

    @Test
    void listSourceJob() throws Exception {
        assertThat(sameEitherWay(get("/sourceJob.json/listSourceJob"))).contains("\"status\":\"SUCCESS\"").contains("\"jobName\":\"claims\"");
    }

    @Test
    void fetchSourceJobDetailWithSourceJobId() throws Exception {
        assertThat(sameEitherWay(get("/sourceJob.json/fetchSourceJobDetailWithSourceJobId").param("jobId", String.valueOf(JOB))))
            .contains("\"status\":\"SUCCESS\"").contains("\"taskName\":\"claims task\"").contains("\"scheduler\"");
    }

    @Test
    void fetchSourceJobQueueListWithJobId() throws Exception {
        assertThat(sameEitherWay(get("/sourceJob.json/fetchSourceJobQueueListWithJobId").param("jobId", String.valueOf(JOB))))
            .contains("\"status\":\"SUCCESS\"").contains("\"jobQueueId\":" + RUN);
    }

    @Test
    void findSourceJobAuditLog() throws Exception {
        assertThat(sameEitherWay(get("/sourceJob.json/findSourceJobAuditLog")
            .param("jobQueueId", String.valueOf(RUN)).param("jobId", String.valueOf(JOB))))
            .contains("\"status\":\"SUCCESS\"").contains("\"logsDetail\":\"line\"").contains("\"sourceJobQueue\"");
    }

    /** MIG-67 made it answer a DTO; the engine is mocked, so running it twice changes nothing it reads. */
    @Test
    void runSourceJob() throws Exception {
        assertThat(sameEitherWay(post("/sourceJob.json/runSourceJob").contentType(MediaType.APPLICATION_JSON)
            .content("{\"jobId\":" + JOB + "}"))).contains("\"status\":\"SUCCESS\"").contains("\"jobId\":" + JOB);
    }

    /** MIG-67 made it answer a DTO. It moves the schedule on, so the schedule is put back before each call. */
    @Test
    void skipNextSourceJob() throws Exception {
        RequestBuilder skip = post("/sourceJob.json/skipNextSourceJob").contentType(MediaType.APPLICATION_JSON)
            .content("{\"jobId\":" + JOB + "}");
        this.resetSchedule();
        MvcResult before = openInView.perform(skip).andReturn();
        this.resetSchedule();
        MvcResult after = noSession.perform(skip).andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(before.getResponse().getStatus()).isEqualTo(200);
        assertThat(after.getResponse().getContentAsString()).isEqualTo(before.getResponse().getContentAsString())
            .contains("\"status\":\"SUCCESS\"").contains("\"schedulerId\":" + SCHEDULER);
    }

    private void resetSchedule() {
        db.sql().update("UPDATE scheduler SET next_run_at = '2026-12-01 09:00:00', expired = false, date_updated = NULL WHERE scheduler_id = ?", SCHEDULER);
    }

    /**
     * The one endpoint MIG-261 changed: with open-in-view off it answered 500 until its @Transactional was put back
     * (TenantFilterRunsInATransactionTest). Pinned byte for byte as open-in-view rendered it before.
     */
    @Test
    void fetchSourceTaskWithSourceTaskId() throws Exception {
        assertThat(sameEitherWay(get("/sourceTask.json/fetchSourceTaskWithSourceTaskId").param("sourceTaskId", String.valueOf(TASK))))
            .isEqualTo("{\"status\":\"SUCCESS\",\"message\":\"SourceTask found with 7301.\",\"data\":{\"taskDetailId\":7301,"
                + "\"tenantId\":2905,\"taskName\":\"claims task\",\"taskStatus\":\"Active\",\"pipelineId\":\"F-CLAIMS\","
                + "\"sourceTaskType\":{\"sourceTaskTypeId\":7300,\"serviceName\":\"worker\",\"description\":\"d\","
                + "\"queueTopicPartition\":\"topic=scrapping-topic&partitions=[*]\",\"kafkaConnectionProfileId\":7290},"
                + "\"xmlTagsInfo\":[{\"taskPayloadId\":7302,\"tagKey\":\"bucket\",\"tagParent\":null,\"tagValue\":\"b\"}]}}");
    }

    @Test
    void listSourceTask() throws Exception {
        assertThat(sameEitherWay(post("/sourceTask.json/listSourceTask").param("startDate", "2000-01-01").param("endDate", "2100-01-01")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))).contains("\"status\":\"SUCCESS\"").contains("claims task");
    }

    @Test
    void fetchAllLinkJobsWithSourceTaskId() throws Exception {
        assertThat(sameEitherWay(post("/sourceTask.json/fetchAllLinkJobsWithSourceTaskId").param("sourceTaskId", String.valueOf(TASK))
            .param("startDate", "2000-01-01").param("endDate", "2100-01-01").contentType(MediaType.APPLICATION_JSON).content("{}")))
            .contains("\"status\":\"SUCCESS\"").contains("\"jobName\":\"claims\"");
    }

    @Test
    void myActivity() throws Exception {
        assertThat(sameEitherWay(get("/sourceJob.json/myActivity"))).contains("\"status\":\"SUCCESS\"");
    }

    @Test
    void pipelineListForms() throws Exception {
        assertThat(sameEitherWay(get("/pipeline.json/listForms"))).contains("\"status\":\"SUCCESS\"").contains("F-CLAIMS");
    }

    @Test
    void pipelineFields() throws Exception {
        assertThat(sameEitherWay(get("/pipeline.json/fields").param("pipelineKey", String.valueOf(PIPELINE))))
            .contains("\"status\":\"SUCCESS\"").contains("\"tagKey\":\"summary\"");
    }

    @Test
    void pipelineFormForPipeline() throws Exception {
        assertThat(sameEitherWay(get("/pipeline.json/formForPipeline").param("pipelineId", "F-CLAIMS")))
            .contains("\"status\":\"SUCCESS\"").contains("\"pipelineName\":\"Claims\"").contains("\"tagKey\":\"summary\"");
    }

    @Test
    void pipelineListForTopic() throws Exception {
        assertThat(sameEitherWay(get("/pipeline.json/listForTopic").param("sourceTaskTypeId", String.valueOf(TYPE))))
            .contains("\"status\":\"SUCCESS\"").contains("F-CLAIMS");
    }

    /** No service and no transaction: the controller reads the run, its job and the pipeline itself. */
    @Test
    void internalVerifyCallback() throws Exception {
        assertThat(sameEitherWay(post("/internal/runs/" + RUN + "/verify-callback").header("X-Internal-Token", "internal-token")
            .contentType(MediaType.APPLICATION_JSON).content("{\"variant\":\"callback\",\"jobId\":" + JOB + ",\"token\":\"t\"}")))
            .contains("\"valid\":true").contains("\"pipelineId\":\"F-CLAIMS\"").contains("\"promptId\":42");
    }

    @Test
    void meterVerifyRun() throws Exception {
        assertThat(sameEitherWay(post("/meter.json/verifyRun").header("X-Worker-Token", "t").contentType(MediaType.APPLICATION_JSON)
            .content("{\"jobId\":" + JOB + ",\"jobQueueId\":" + RUN + "}"))).contains("\"tenantId\":" + TENANT);
    }

    /** The control: with the session held open the lazy job renders; without it, it cannot. */
    @Test
    void lazyEntityProbe() throws Exception {
        RequestBuilder probe = get("/probe/run").param("jobQueueId", String.valueOf(RUN));
        assertThat(openInView.perform(probe).andReturn().getResponse().getContentAsString()).contains("\"jobName\":\"claims\"");
        MvcResult after = noSession.perform(probe).andReturn();
        assertThat(after.getResponse().getStatus()).isEqualTo(500);
        assertThat(after.getResolvedException()).hasRootCauseInstanceOf(LazyInitializationException.class);
    }
}
