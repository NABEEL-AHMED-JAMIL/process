package process.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import process.model.enums.PageKey;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.model.service.PageAccessService;
import process.security.PageAccessCache;
import process.security.PageGate;
import process.security.TenantContext;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The page gate for the services that left process. Their routes pass the gateway, which asks here
 * before it forwards a tenant user's call: the same PageKey catalogue, the same effective pages, the
 * same 403 sentence the interceptor writes. The caller's own token is verified by process's filter
 * on the way in; the gateway only forwards it, so it never has to trust a claim it did not check.
 */
class InternalPageAccessRestApiTest {

    private static final String TOKEN = "t0ken";

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PageAccessService pages = mock(PageAccessService.class);
    private final PageGate gate = new PageGate(this.users, this.pages, new PageAccessCache());
    private final InternalPageAccessRestApi api = new InternalPageAccessRestApi(this.gate, TOKEN);

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void tenantUser(PageKey... held) {
        AppUser user = new AppUser();
        user.setAppUserId(44L);
        when(this.users.findById(44L)).thenReturn(Optional.of(user));
        when(this.pages.effectivePages(any())).thenReturn(held.length == 0 ? EnumSet.noneOf(PageKey.class) : EnumSet.of(held[0], held));
        TenantContext.set(1001L, "TENANT_USER", 44L, "olivia@a.example");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ask(String path) {
        ResponseEntity<?> answer = this.api.check(TOKEN, "Bearer caller-token", Collections.singletonMap("path", path));
        assertThat(answer.getStatusCodeValue()).isEqualTo(200);
        return (Map<String, Object>) answer.getBody();
    }

    /**
     * The gateway's revocation check (MIG-14 follow-up). A bearer token process's filter refused --
     * signed out, minted before a demotion, expired, forged -- leaves no caller; the answer says so, and
     * the gateway refuses the call with a 401 instead of forwarding it to a service that may not yet
     * check revocations itself.
     */
    @Test
    void aBearerTokenTheFilterRefusedIsAnsweredUnauthenticated() {
        TenantContext.clear();

        Map<String, Object> answer = ask("/documentConverter.json/convert");

        assertThat(answer).containsEntry("allowed", false).containsEntry("authenticated", false)
            .containsEntry("message", "Sign in to continue.");
    }

    /** No bearer token at all: nothing to gate, as before -- the service refuses an anonymous call itself. */
    @Test
    void noBearerTokenIsNoCallerToGate() {
        TenantContext.clear();
        ResponseEntity<?> answer = this.api.check(TOKEN, null, Collections.singletonMap("path", "/documentConverter.json/convert"));
        assertThat(answer.getStatusCodeValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) answer.getBody()).get("allowed")).isEqualTo(true);
    }

    @Test
    void withoutTheServiceTokenNothingIsAnswered() {
        assertThat(this.api.check(null, "Bearer caller-token", Collections.singletonMap("path", "/billing.json")).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.check("wrong", "Bearer caller-token", Collections.singletonMap("path", "/billing.json")).getStatusCodeValue()).isEqualTo(401);
        verifyNoInteractions(this.users, this.pages);
    }

    @Test
    void aTenantUserWithoutThePageIsRefusedInTheInterceptorsOwnWords() {
        tenantUser(PageKey.JOBS);
        Map<String, Object> answer = ask("/documentConverter.json/convert");
        assertThat(answer).containsEntry("allowed", false)
            .containsEntry("message", "Document Converter is not part of your access. Ask your workspace admin.");
    }

    @Test
    void aTenantUserWithThePageAndAnyoneWhoIsNotATenantUserPass() {
        tenantUser(PageKey.ANALYTICS_DASHBOARDS);
        // Either analytics page opens all six analytics prefixes.
        assertThat(ask("/analyticsLibrary.json/listQueries")).containsEntry("allowed", true);
        TenantContext.set(1001L, "TENANT_ADMIN", 9L, "admin@a.example");
        assertThat(ask("/documentConverter.json/convert")).containsEntry("allowed", true);
    }

    /**
     * MIG-99: the gateway keeps each answer for 15 seconds, the bound ADR-019 promises. If this
     * endpoint answered from process's own 15-second cache as well, the two would stack and a
     * revoked page could stay open for up to 30. So the check the gateway asks resolves fresh:
     * a revocation is refused on the very next ask, and the gateway's TTL is the whole bound.
     */
    @Test
    void aRevokedPageIsRefusedOnTheNextAskNotAfterProcesssOwnCache() {
        tenantUser(PageKey.TOOLS_CONVERTER);
        assertThat(ask("/documentConverter.json/convert")).containsEntry("allowed", true);

        when(this.pages.effectivePages(any())).thenReturn(EnumSet.of(PageKey.JOBS));

        assertThat(ask("/documentConverter.json/convert")).containsEntry("allowed", false);
    }

    @Test
    void anUngatedPathPassesAndNoProfileIsRead() {
        TenantContext.set(1001L, "TENANT_USER", 44L, "olivia@a.example");
        assertThat(ask("/storage.json/listBuckets")).containsEntry("allowed", true);
        verifyNoInteractions(this.pages);
    }

    @Test
    void aTenantUserTokenWithoutAUserIdIsRefused() {
        TenantContext.set(1001L, "TENANT_USER", null, "olivia@a.example");
        assertThat(ask("/fileShare.json/share")).containsEntry("allowed", false);
    }
}
