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
 * MIG-91: the page-access gate's refusal, byte for byte, and the fail-closed paths MIG-12 closed.
 *
 * The 403 is hand-written -- Gson over a ResponseDto -- so it arrives in the envelope the console
 * already reads for every other refusal and needs no special case. A gateway or a new service that
 * answers the same refusal in a different shape breaks the console's error handling silently, so
 * the body is pinned as a whole string, not by a contains().
 */
@ExtendWith(MockitoExtension.class)
class PageGateCharacterisationTest {

    private static final long USER = 44L;

    @Mock private AppUserRepository appUserRepository;
    @Mock private PageAccessService pageAccessService;

    private PageAccessCache cache;
    private PageAccessInterceptor interceptor;
    private PageGate gate;

    @BeforeEach
    void setUp() {
        this.cache = new PageAccessCache();
        this.interceptor = new PageAccessInterceptor(this.appUserRepository, this.pageAccessService, this.cache);
        this.gate = new PageGate(this.appUserRepository, this.pageAccessService, this.cache);
        AppUser user = new AppUser();
        user.setAppUserId(USER);
        lenient().when(this.appUserRepository.findById(USER)).thenReturn(Optional.of(user));
        lenient().when(this.pageAccessService.effectivePages(any())).thenReturn(EnumSet.of(PageKey.JOBS));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletResponse call(String servletPath) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1" + servletPath);
        request.setServletPath(servletPath);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean passed = this.interceptor.preHandle(request, response, new Object());
        assertThat(passed).isEqualTo(response.getStatus() == 200);
        return response;
    }

    @Test
    void theRefusalIsTheConsolesOwnEnvelopeAndNothingElse() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", USER, "olivia@a.example");

        MockHttpServletResponse response = this.call("/report.json/fetchReports");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getCharacterEncoding()).isEqualTo("UTF-8");
        // No data, no paging, no stack, no path: the two fields every refusal carries.
        assertThat(response.getContentAsString())
            .isEqualTo("{\"status\":\"ERROR\",\"message\":\"Reports is not part of your access. Ask your workspace admin.\"}");
    }

    /** The label is the first gating page's, in PageKey order, when a group is behind several. */
    @Test
    void aGroupBehindTwoPagesNamesTheFirstInTheRefusal() throws Exception {
        when(this.pageAccessService.effectivePages(any())).thenReturn(EnumSet.noneOf(PageKey.class));
        TenantContext.set(1001L, "TENANT_USER", USER, "olivia@a.example");

        PageGate.Decision decision = this.gate.decide("TENANT_USER", USER, "/message.json/fetchLogs");

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getMessage()).isEqualTo(String.format("%s is not part of your access. Ask your workspace admin.",
            PageKey.pagesGating("/message.json/fetchLogs").iterator().next().getLabel()));
    }

    /** MIG-12, restated as a decision: no user id is no pages, never PageKey.all(). */
    @Test
    void aTenantUserWithNoIdHoldsNothingAndIsNeverLookedUp() {
        PageGate.Decision decision = this.gate.decide("TENANT_USER", null, "/sourceJob.json/listSourceJob");

        assertThat(decision.isAllowed()).isFalse();
        verify(this.appUserRepository, never()).findById(any());
        verify(this.pageAccessService, never()).effectivePages(any());
    }

    /** MIG-12: an id whose row is gone resolves to no pages, not to everything. */
    @Test
    void anUnknownIdResolvesToNoPagesEvenFresh() {
        when(this.appUserRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(this.gate.decide("TENANT_USER", 99L, "/sourceJob.json/listSourceJob").isAllowed()).isFalse();
        assertThat(this.gate.decideFresh("TENANT_USER", 99L, "/sourceJob.json/listSourceJob").isAllowed()).isFalse();
        verify(this.pageAccessService, never()).effectivePages(any());
    }

    /** A role the gate does not know is not a tenant user, so today it passes: only TENANT_USER is gated. */
    @Test
    void onlyTheTenantUserRoleIsGatedWhateverElseTheTokenSays() {
        for (String role : new String[] {"TENANT_ADMIN", "PLATFORM_ADMIN", "tenant_user", "", null}) {
            assertThat(this.gate.decide(role, USER, "/report.json/fetchReports").isAllowed()).as(String.valueOf(role)).isTrue();
        }
        verify(this.pageAccessService, never()).effectivePages(any());
    }

    /** The gateway's read skips this instance's cache, so two TTLs never stack (MIG-99). */
    @Test
    void theFreshDecisionReadsTheDatabaseEveryTime() {
        this.gate.decide("TENANT_USER", USER, "/sourceJob.json/a");
        this.gate.decideFresh("TENANT_USER", USER, "/sourceJob.json/a");
        this.gate.decideFresh("TENANT_USER", USER, "/sourceJob.json/a");

        verify(this.pageAccessService, times(3)).effectivePages(any());
    }
}
