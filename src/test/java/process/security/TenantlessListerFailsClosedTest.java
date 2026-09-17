package process.security;

import org.hibernate.Filter;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.service.impl.QueryService;

import javax.persistence.EntityManager;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * A caller that arrives with no tenant of its own, on the paths that list rather than fetch by id.
 *
 * The by-id checks already fail closed and say so: TenantOwnership is documented as "A context
 * with no tenant owns nothing, so it is refused outright", and a test already covers it. The list
 * paths did the opposite. QueryService.tenantClause returned no predicate at all for a null
 * tenant, and TenantFilterHelper treated a null tenant exactly like a platform admin and
 * *disabled* the tenant filter -- so the same account that could not open one job by id was
 * served every tenant's jobs by the list, and every tenant's tasks including their task_payload
 * XML. That state is reachable from a legacy app_user row: TenantSeedService backfills a tenant
 * onto jobs, tasks, agents and buckets, and deliberately not onto app_user.
 *
 * These pin both halves to the by-id rule: only PLATFORM_ADMIN crosses tenants, and no tenant
 * means no rows.
 */
@ExtendWith(MockitoExtension.class)
public class TenantlessListerFailsClosedTest {

    @Mock private EntityManager entityManager;
    @Mock private Session session;
    @Mock private Filter filter;

    private TenantFilterHelper helper;

    @BeforeEach
    void setUp() {
        this.helper = new TenantFilterHelper();
        lenient().when(this.entityManager.unwrap(Session.class)).thenReturn(this.session);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ---- the JPQL side: the Hibernate filter --------------------------------------------------

    @Test
    void aTenantUserWithNoTenantIsFilteredToNothingRatherThanEverything() {
        TenantContext.set(null, "TENANT_USER", 1L, "orphan@example.com");
        when(this.session.enableFilter("tenantFilter")).thenReturn(this.filter);

        this.helper.enableIfNeeded(this.entityManager);

        // Enabled, not disabled -- and bound to a tenant no row can carry.
        verify(this.session).enableFilter("tenantFilter");
        verify(this.session, never()).disableFilter("tenantFilter");
        verify(this.filter).setParameter("tenantId", -1L);
    }

    @Test
    void aTenantUserWithATenantIsFilteredToIt() {
        TenantContext.set(2002L, "TENANT_USER", 1L, "b@example.com");
        when(this.session.enableFilter("tenantFilter")).thenReturn(this.filter);

        this.helper.enableIfNeeded(this.entityManager);

        verify(this.filter).setParameter("tenantId", 2002L);
    }

    /** The one role that legitimately crosses tenants still does. */
    @Test
    void aPlatformAdminStillSeesEveryTenant() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        when(this.session.getEnabledFilter("tenantFilter")).thenReturn(this.filter);

        this.helper.enableIfNeeded(this.entityManager);

        verify(this.session).disableFilter("tenantFilter");
        verify(this.session, never()).enableFilter("tenantFilter");
    }

    // ---- the native side: the spliced SQL predicate ---------------------------------------------

    private static String tenantClauseFor(String alias) throws Exception {
        Method method = QueryService.class.getDeclaredMethod("tenantClause", String.class);
        method.setAccessible(true);
        return (String) method.invoke(new QueryService(), alias);
    }

    @Test
    void theListQueryPredicateMatchesNothingWithoutATenant() throws Exception {
        TenantContext.set(null, "TENANT_USER", 1L, "orphan@example.com");

        String clause = tenantClauseFor("sj");

        // An empty clause here is the whole defect: it made the list unscoped.
        assertThat(clause).isNotEmpty();
        assertThat(clause).contains("1 = 0");
    }

    @Test
    void theListQueryPredicateScopesToTheCallersTenant() throws Exception {
        TenantContext.set(2002L, "TENANT_USER", 1L, "b@example.com");

        assertThat(tenantClauseFor("sj")).isEqualTo(" and sj.tenant_id = 2002 ");
    }

    @Test
    void aPlatformAdminGetsNoPredicateAtAll() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");

        assertThat(tenantClauseFor("sj")).isEmpty();
    }

    /** No context set at all -- a path that forgot to populate it -- is untrusted, not trusted. */
    @Test
    void anEmptyContextIsAlsoFilteredToNothing() throws Exception {
        assertThat(tenantClauseFor("st")).contains("1 = 0");
    }

}
