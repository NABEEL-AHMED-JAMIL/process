package process.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import process.model.enums.PageKey;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.model.service.PageAccessService;

import java.util.EnumSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The server-side half: a call under a gated API group is refused for a tenant user whose
 * profile does not open the page, and nobody else is touched.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class PageAccessInterceptorTest {

    private static final long USER = 44L;

    @Mock private AppUserRepository appUserRepository;
    @Mock private PageAccessService pageAccessService;

    private PageAccessCache cache;
    private PageAccessInterceptor interceptor;

    @BeforeEach
    void setUp() {
        this.cache = new PageAccessCache();
        this.interceptor = new PageAccessInterceptor(this.appUserRepository, this.pageAccessService, this.cache);
        AppUser user = new AppUser();
        user.setAppUserId(USER);
        lenient().when(this.appUserRepository.findById(USER)).thenReturn(Optional.of(user));
        lenient().when(this.pageAccessService.effectivePages(any())).thenReturn(EnumSet.of(PageKey.JOBS, PageKey.QUEUE));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequest request(String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1" + servletPath);
        request.setServletPath(servletPath);
        return request;
    }

    private boolean call(String servletPath, MockHttpServletResponse response) throws Exception {
        return this.interceptor.preHandle(this.request(servletPath), response, new Object());
    }

    @Test
    void aTenantUserIsRefusedOnAPageTheirProfileDoesNotOpen() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", USER, "olivia@a.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(this.call("/report.json/fetchReports", response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).contains("Reports is not part of your access");
    }

    @Test
    void aTenantUserPassesOnAPageTheyHold() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", USER, "olivia@a.example");
        assertThat(this.call("/sourceJob.json/listSourceJob", new MockHttpServletResponse())).isTrue();
    }

    /** /message.json is behind Queue AND Reports: holding either is enough. */
    @Test
    void aSharedApiGroupIsOpenToAnyOfItsPages() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", USER, "olivia@a.example");
        assertThat(this.call("/message.json/fetchLogs", new MockHttpServletResponse())).isTrue();
    }

    @Test
    void anUngatedPathIsNeverChecked() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", USER, "olivia@a.example");
        assertThat(this.call("/dashboard.json/summary", new MockHttpServletResponse())).isTrue();
        assertThat(this.call("/storage.json/uploadObject", new MockHttpServletResponse())).isTrue();
        assertThat(this.call("/notification.json/list", new MockHttpServletResponse())).isTrue();
        verify(this.pageAccessService, never()).effectivePages(any());
    }

    /** The profile screen's own-activity call sits under /sourceJob.json but is about the person, not the page. */
    @Test
    void ownActivityStaysOpenWithoutTheJobsPage() throws Exception {
        when(this.pageAccessService.effectivePages(any())).thenReturn(EnumSet.noneOf(PageKey.class));
        TenantContext.set(1001L, "TENANT_USER", USER, "olivia@a.example");
        assertThat(this.call("/sourceJob.json/myActivity", new MockHttpServletResponse())).isTrue();
        assertThat(this.call("/sourceJob.json/listSourceJob", new MockHttpServletResponse())).isFalse();
    }

    @Test
    void adminsAreNeverSubjectToAProfile() throws Exception {
        TenantContext.set(1001L, "TENANT_ADMIN", 9L, "admin@a.example");
        assertThat(this.call("/report.json/fetchReports", new MockHttpServletResponse())).isTrue();
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "platform@example.com");
        assertThat(this.call("/analytics.json/run", new MockHttpServletResponse())).isTrue();
        verify(this.pageAccessService, never()).effectivePages(any());
    }

    @Test
    void theAnswerIsRememberedBetweenCallsAndForgottenOnChange() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", USER, "olivia@a.example");
        this.call("/sourceJob.json/a", new MockHttpServletResponse());
        this.call("/sourceJob.json/b", new MockHttpServletResponse());
        verify(this.pageAccessService, times(1)).effectivePages(any());

        this.cache.forget(USER);
        this.call("/sourceJob.json/c", new MockHttpServletResponse());
        verify(this.pageAccessService, times(2)).effectivePages(any());
    }

    /** MIG-12: a tenant user's token with no appUserId claim is refused on every gated page, not waved through. */
    @Test
    void aTenantUserWithNoUserIdIsRefusedOnEveryGatedPage() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", null, "olivia@a.example");
        for (String path : new String[] {"/sourceJob.json/listSourceJob", "/report.json/fetchReports", "/documentConverter.json/list",
                "/analytics.json/run"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(this.call(path, response)).as(path).isFalse();
            assertThat(response.getStatus()).as(path).isEqualTo(403);
        }
    }

    /** MIG-12: a valid token whose user row is gone holds no pages, rather than every page. */
    @Test
    void aTenantUserWhoseRowIsGoneHoldsNoPages() throws Exception {
        when(this.appUserRepository.findById(USER)).thenReturn(Optional.empty());
        TenantContext.set(1001L, "TENANT_USER", USER, "olivia@a.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(this.call("/sourceJob.json/listSourceJob", response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }
}
