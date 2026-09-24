package process.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.hibernate.Filter;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.api.SourceTaskRestApi;
import process.model.repository.SourceTaskRepository;
import process.model.service.impl.SourceTaskServiceImpl;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * MIG-11 (DEF-010): when the tenant filter cannot be turned on, the request fails -- loudly, in the
 * log and in the response -- instead of running the query unfiltered.
 *
 * enableIfNeeded used to catch the failure, log it at ERROR and return, and the caller went on to
 * read every tenant's rows. A JPA provider change, a proxy, or a non-Hibernate EntityManager was
 * therefore a cross-tenant read that only an ERROR line nobody reads would show. These are the
 * induced-failure cases the cross-tenant attack suite (D6) asks for, driven through a real endpoint.
 */
class TenantFilterFailsClosedTest {

    private final TenantFilterHelper helper = new TenantFilterHelper();
    private ListAppender<ILoggingEvent> log;
    private Logger logger;

    @BeforeEach
    void captureLog() {
        this.logger = (Logger) LoggerFactory.getLogger(TenantFilterHelper.class);
        this.log = new ListAppender<>();
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void tearDown() {
        this.logger.detachAppender(this.log);
        TenantContext.clear();
    }

    private EntityManager cannotUnwrap() {
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.unwrap(Session.class)).thenThrow(new PersistenceException("not a Hibernate session"));
        return entityManager;
    }

    private EntityManager cannotEnable() {
        Session session = mock(Session.class);
        when(session.enableFilter(anyString())).thenThrow(new HibernateException("No such filter configured [tenantFilter]"));
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        return entityManager;
    }

    /** One real endpoint whose service turns the filter on before it reads: GET a source task by id. */
    private MvcResult fetchTask(EntityManager entityManager, SourceTaskRepository tasks) throws Exception {
        SourceTaskServiceImpl service = new SourceTaskServiceImpl(null, null, null, tasks, null, this.helper,
            null, null, null, null);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new SourceTaskRestApi(service)).build();
        return mvc.perform(get("/sourceTask.json/fetchSourceTaskWithSourceTaskId").param("sourceTaskId", "7")).andReturn();
    }

    private void loudInTheLog() {
        assertThat(this.log.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("tenant filter");
        });
    }

    @Test
    void aSessionThatCannotBeUnwrappedFailsTheRequestAndReadsNothing() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", 5L, "olivia@a.example");
        SourceTaskRepository tasks = mock(SourceTaskRepository.class);

        MvcResult result = this.fetchTask(this.cannotUnwrap(), tasks);

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"data\"");
        verifyNoInteractions(tasks);
        this.loudInTheLog();
    }

    @Test
    void aFilterThatCannotBeEnabledFailsTheRequestAndReadsNothing() throws Exception {
        TenantContext.set(1001L, "TENANT_ADMIN", 5L, "admin@a.example");
        SourceTaskRepository tasks = mock(SourceTaskRepository.class);

        MvcResult result = this.fetchTask(this.cannotEnable(), tasks);

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"data\"");
        verifyNoInteractions(tasks);
        this.loudInTheLog();
    }

    @Test
    void bothFailuresThrowTheDedicatedExceptionWithTheCause() {
        TenantContext.set(1001L, "TENANT_USER", 5L, "olivia@a.example");
        assertThatThrownBy(() -> this.helper.enableIfNeeded(this.cannotUnwrap()))
            .isInstanceOf(TenantIsolationException.class).hasCauseInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> this.helper.enableIfNeeded(this.cannotEnable()))
            .isInstanceOf(TenantIsolationException.class).hasCauseInstanceOf(HibernateException.class);
    }

    /** The tenantless caller filtered to nothing (TenantlessListerFailsClosedTest) fails the same way when it cannot be. */
    @Test
    void aTenantlessCallerFailsClosedToo() {
        TenantContext.set(null, "TENANT_USER", 5L, "orphan@a.example");
        assertThatThrownBy(() -> this.helper.enableIfNeeded(this.cannotEnable())).isInstanceOf(TenantIsolationException.class);
    }

    /** A platform admin runs unfiltered on purpose, and still does. */
    @Test
    void aPlatformAdminStillRunsUnfiltered() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        Session session = mock(Session.class);
        when(session.getEnabledFilter("tenantFilter")).thenReturn(mock(Filter.class));
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.unwrap(Session.class)).thenReturn(session);

        this.helper.enableIfNeeded(entityManager);

        verify(session).disableFilter("tenantFilter");
        verify(session, never()).enableFilter(anyString());
    }

    /**
     * A platform admin whose session cannot be unwrapped fails too. Its risk is seeing too little
     * rather than too much, but a JPA setup that cannot hand over a Session is broken for everybody,
     * and it should say so the first time anybody reaches it.
     */
    @Test
    void aPlatformAdminWithABrokenSessionFailsToo() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        assertThatThrownBy(() -> this.helper.enableIfNeeded(this.cannotUnwrap())).isInstanceOf(TenantIsolationException.class);
    }

    /** Each catch in process/security that does not throw, and why falling through it is closed rather than open. */
    private static final Map<String, String> EXPLAINED = new HashMap<>();

    static {
        EXPLAINED.put("JwtAuthenticationFilter.java catch (JwtException | IllegalArgumentException ex)",
            "a token that cannot be read leaves the request anonymous, and Spring Security refuses an anonymous request "
                + "to anything that is not permitAll");
        EXPLAINED.put("JwtAuthenticationFilter.java catch (TokenRevocations.Unavailable ex)",
            "revocations cannot be checked, so the token authenticates nobody: stillGood answers false (MIG-14)");
        EXPLAINED.put("TokenRevocations.java catch (Unavailable ex)",
            "publishing a bumped version failed after the database took it; a cached older version expires within "
                + "VERSION_TTL (60 s), and while Redis is away isRevoked throws and every token is refused");
    }

    /**
     * The grep, kept: no catch block in process/security ends the method quietly. Each one throws,
     * or is listed here with the reason its fall-through is closed rather than open.
     */
    @Test
    void noCatchInTheSecurityPackageSwallowsAndReturns() throws Exception {
        List<String> quiet = new ArrayList<>();
        Pattern catchBlock = Pattern.compile("catch\\s*\\(([^)]*)\\)\\s*\\{(.*?)\\n\\s*}", Pattern.DOTALL);
        File[] sources = new File("src/main/java/process/security").listFiles((dir, name) -> name.endsWith(".java"));
        assertThat(sources).isNotEmpty();
        for (File source : sources) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            Matcher m = catchBlock.matcher(text);
            while (m.find()) {
                String where = source.getName() + " catch (" + m.group(1).trim() + ")";
                if (m.group(2).contains("throw ")) {
                    continue;
                }
                if (EXPLAINED.containsKey(where)) {
                    continue;
                }
                quiet.add(where);
            }
        }
        assertThat(quiet).as("catch blocks that neither throw nor are explained").isEmpty();
    }
}
