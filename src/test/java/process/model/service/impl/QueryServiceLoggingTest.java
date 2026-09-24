package process.model.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.SearchTextDto;
import process.security.TenantContext;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-74 (DEF-142): QueryService's three executors logged every composed query at INFO -- and every
 * query in the class is string-built, so the line carried the caller's tenant id, job ids and whatever
 * they typed into a search box. A log shipper feeding a shared index would cross the tenancy boundary
 * outside the database, where neither the Hibernate filters nor tenantClause reach.
 *
 * The decision (recorded on MIG-74): the executors log the query's SHAPE at DEBUG, every literal
 * replaced by '?', and the composed query itself only at TRACE. The shape is what a developer reads to
 * see which joins and predicates were built; the values are what a tenant must not find in a shared
 * index. DEBUG alone would not have been enough: logback.xml runs the process logger at DEBUG in every
 * environment, so a plain drop to DEBUG would have changed nothing that ships.
 *
 * Checked at every level a deployment runs the logger at, including prod's WARN and the DEBUG that
 * logback.xml actually configures: a list request and a dashboard request, both carrying a tenant and
 * the list a search term, and neither the term nor the tenant id may appear.
 */
class QueryServiceLoggingTest {

    private static final long TENANT = 2901L;
    private static final String SEARCH = "Quarterly Payroll Zeta";

    private final QueryService queries = new QueryService();
    private final Logger logger = (Logger) LoggerFactory.getLogger(QueryService.class);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private Level configured;

    @BeforeEach
    void capture() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(Collections.emptyList());
        when(query.getSingleResult()).thenReturn(0L);
        ReflectionTestUtils.setField(this.queries, "_em", em);
        TenantContext.set(TENANT, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        this.configured = this.logger.getLevel();
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void release() {
        this.logger.detachAppender(this.log);
        this.logger.setLevel(this.configured);
        TenantContext.clear();
    }

    enum Deployed {
        WARN(Level.WARN), INFO(Level.INFO), DEBUG(Level.DEBUG);

        private final Level level;

        Deployed(Level level) {
            this.level = level;
        }
    }

    @ParameterizedTest
    @EnumSource(Deployed.class)
    void neitherTheTenantNorTheSearchTermIsLogged(Deployed deployed) {
        this.logger.setLevel(deployed.level);
        SearchTextDto search = new SearchTextDto();
        search.setItemName("task_name");
        search.setItemValue(SEARCH);

        this.queries.executeQuery(this.queries.listSourceTaskQuery(false, null, null, null, null, search), PageRequest.of(0, 10));
        this.queries.executeQueryForSingleResult(this.queries.listSourceTaskQuery(true, null, null, null, null, search));
        this.queries.executeQuery(this.queries.weeklyHrRunningStatisticsDimension("2026-09-21", 14L));

        String logged = this.lines();
        assertThat(logged).doesNotContain(String.valueOf(TENANT));
        assertThat(logged.toLowerCase()).doesNotContain("payroll");
    }

    @ParameterizedTest
    @EnumSource(Deployed.class)
    void debugStillShowsWhichQueryRan(Deployed deployed) {
        this.logger.setLevel(deployed.level);

        this.queries.executeQuery(this.queries.weeklyHrRunningStatisticsDimension("2026-09-21", 14L));

        if (deployed.level.isGreaterOrEqual(Level.INFO)) {
            assertThat(this.log.list).as("nothing at %s", deployed).isEmpty();
        } else {
            assertThat(this.lines()).contains("INNER JOIN source_job ON source_job.job_id = job_queue.job_id")
                .contains("source_job.tenant_id = ?").contains("DATE(job_queue.date_created AT TIME ZONE ?) = ?");
        }
    }

    private String lines() {
        List<String> lines = this.log.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
        return String.join("\n", lines);
    }
}
