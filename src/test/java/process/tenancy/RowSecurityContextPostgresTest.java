package process.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.barco.platform.tenancy.RowSecurityDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.config.RowSecurityConfig;
import process.directory.TenantOrphanAudit;
import process.directory.WorkspaceRetirement;
import process.identity.IdentityPort;
import process.security.RunWorkspace;
import process.security.TenantContext;

import javax.sql.DataSource;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-258's hooks in a Spring context, wired as ModelApplication wires them: RowSecurityConfig imports platform-commons'
 * configuration, the pool (SET ROLE process_app, as application-*.properties says) becomes a RowSecurityDataSource
 * reading Core's TenantContext, and the @AcrossTenants beans come out of the context behind their proxy. The whole
 * application needs Kafka, Redis and S3 to start, so this starts the part row security changes, on a scratch etl_job.
 */
class RowSecurityContextPostgresTest {

    static final long A = 8901L;
    static final long B = 8902L;

    private static ScratchPostgres db;
    private static AnnotationConfigApplicationContext context;

    @BeforeAll
    static void start() throws Exception {
        db = ScratchPostgres.create("rls_context");
        JdbcTemplate login = db.jdbc();
        for (long tenant : new long[]{A, B}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "c" + tenant,
                "C" + tenant);
            login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Manual', 'ctx', 'Active', 1, ?)", tenant * 10, tenant);
            login.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, job_send, attempt) "
                + "VALUES (?, ?, 'Queue', now(), now(), 'Active', false, 1)", tenant * 100, tenant * 10);
        }
        IdentityPort identity = mock(IdentityPort.class);
        when(identity.workspaces(any())).thenReturn(Arrays.asList(new IdentityPort.Workspace(A, "C" + A, "c" + A, "Active"),
            new IdentityPort.Workspace(B, "C" + B, "c" + B, "Active")));
        context = new AnnotationConfigApplicationContext();
        context.register(RowSecurityConfig.class);
        context.registerBean("dataSource", DataSource.class, () -> {
            HikariDataSource pool = new HikariDataSource();
            pool.setJdbcUrl(db.pool().getJdbcUrl());
            pool.setUsername(db.pool().getUsername());
            pool.setPassword(db.pool().getPassword());
            pool.setMaximumPoolSize(4);
            pool.setConnectionInitSql("SET ROLE process_app");
            return pool;
        });
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(context.getBean(DataSource.class)));
        context.registerBean(IdentityPort.class, () -> identity);
        context.registerBean(WorkspaceRetirement.class, () -> new WorkspaceRetirement(context.getBean(JdbcTemplate.class)));
        context.registerBean(TenantOrphanAudit.class, () -> new TenantOrphanAudit(context.getBean(JdbcTemplate.class), identity,
            context.getBean(WorkspaceRetirement.class)));
        context.registerBean(RunWorkspace.class, () -> new RunWorkspace(context.getBean(JdbcTemplate.class)));
        context.refresh();
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) {
            ((HikariDataSource) context.getBean(RowSecurityDataSource.class).getTargetDataSource()).close();
            context.close();
        }
        if (db != null) db.close();
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theDataSourceIsWrappedAndWorksAsProcessApp() {
        assertThat(context.getBean(DataSource.class)).isInstanceOf(RowSecurityDataSource.class);
        assertThat(context.getBean(JdbcTemplate.class).queryForObject("SELECT current_user", String.class)).isEqualTo("process_app");
    }

    @Test
    void theSessionFollowsCoresTenantContext() {
        JdbcTemplate app = context.getBean(JdbcTemplate.class);
        assertThat(app.queryForObject("SELECT count(*) FROM source_job", Long.class)).as("nobody signed in").isZero();
        TenantContext.set(A, "TENANT_ADMIN", 1L, "a@a.example");
        assertThat(app.queryForList("SELECT tenant_id FROM source_job", Long.class)).containsExactly(A);
        TenantContext.set(0L, "TENANT_ADMIN", 1L, "a@a.example");
        assertThat(app.queryForObject("SELECT count(*) FROM source_job", Long.class)).as("workspace 0 is none").isZero();
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example");
        assertThat(app.queryForList("SELECT tenant_id FROM source_job ORDER BY 1", Long.class)).containsExactly(A, B);
    }

    @Test
    void theAcrossTenantsBeansComeOutOfTheContextBehindTheirProxy() {
        assertThat(AopUtils.isAopProxy(context.getBean(TenantOrphanAudit.class))).isTrue();
        assertThat(AopUtils.isAopProxy(context.getBean(RunWorkspace.class))).isTrue();
        TenantOrphanAudit.Report report = context.getBean(TenantOrphanAudit.class).run();
        assertThat(report.isChecked()).isTrue();
        assertThat(report.getOrphans()).isEmpty();
        assertThat(context.getBean(RunWorkspace.class).of(B * 100)).isEqualTo(B);
    }
}
