package process.e2e;

import org.jodconverter.core.document.DocumentFormatRegistry;
import org.jodconverter.core.office.OfficeManager;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.security.TenantContext;
import process.util.JwtUtil;

import java.sql.Timestamp;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shared ground for the end-to-end suites: a whole application, driven over real HTTP.
 *
 * Everything below the request is genuine -- the security filter chain, the JWT parsing, the
 * @PreAuthorize checks, the services, the Hibernate tenant filter and the database. The unit tests
 * elsewhere call a service directly and therefore cannot see any of that; a role rule can be
 * perfect in the service and undone by the annotation above it, and only a request finds out.
 *
 * Two things are mocked, both because they need software a developer's machine does not have:
 * LibreOffice, which the document converter drives. Nothing security-relevant is stubbed.
 *
 * The suites run against the local development database and roll back: @Transactional wraps each
 * test, the request runs on the same thread and so joins that transaction, and nothing survives.
 * That keeps a suite that creates tenants and users from silting up the database it runs on.
 *
 * Tokens are minted with the application's own JwtUtil, for users the test has just created. That
 * is the real signing path, so a token here is exactly what a browser would carry -- there is no
 * test-only way past the filter, which is the property that makes these tests worth having.
 *
 * @author Nabeel Ahmed
 * */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("e2e")
@Transactional
public abstract class E2ESupport {

    @MockBean protected OfficeManager officeManager;
    @MockBean protected DocumentFormatRegistry documentFormatRegistry;

    @Autowired protected MockMvc mvc;
    @Autowired protected JwtUtil jwtUtil;
    @Autowired protected AppUserRepository appUserRepository;
    @Autowired protected TenantRepository tenantRepository;
    @Autowired protected PasswordEncoder passwordEncoder;

    /**
     * The filter sets the context per request and clears it, but a test that calls a service
     * directly would otherwise leak one into the next.
     */
    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /** A short unique suffix, so two runs cannot collide on a unique column. */
    protected String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected Tenant newTenant(String name) {
        Tenant tenant = new Tenant();
        tenant.setTenantName(name + "-" + this.unique());
        tenant.setTenantCode(("code-" + this.unique()).toUpperCase());
        tenant.setStatus(TenantStatus.Active);
        tenant.setDateCreated(new Timestamp(System.currentTimeMillis()));
        return this.tenantRepository.save(tenant);
    }

    protected AppUser newUser(UserRole role, Tenant tenant) {
        AppUser user = new AppUser();
        user.setUsername("e2e-" + this.unique() + "@example.test");
        // Never signed in with; the suite authenticates by minting a token for this row. Hashed
        // anyway so the column holds what the application would have put there.
        user.setPassword(this.passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setFullName("E2E " + role.name());
        user.setUserRole(role);
        user.setStatus(Status.Active);
        user.setMustChangePassword(false);
        user.setTenantId(tenant == null ? null : tenant.getTenantId());
        user.setDateCreated(new Timestamp(System.currentTimeMillis()));
        return this.appUserRepository.save(user);
    }

    /** A platform admin belongs to no tenant, which is what makes it reach every one of them. */
    protected AppUser newPlatformAdmin() {
        return this.newUser(UserRole.PLATFORM_ADMIN, null);
    }

    protected String tokenFor(AppUser user) {
        return this.jwtUtil.generateAccessToken(user);
    }

    // ---- request builders, so a suite reads as a sequence of calls -------------------------

    protected MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, AppUser user) {
        return request.header("Authorization", "Bearer " + this.tokenFor(user))
            .contentType(MediaType.APPLICATION_JSON);
    }

    protected MockHttpServletRequestBuilder getAs(AppUser user, String url, Object... args) {
        return this.asUser(get(String.format(url, args)), user);
    }

    protected MockHttpServletRequestBuilder postAs(AppUser user, String url, String body, Object... args) {
        MockHttpServletRequestBuilder request = post(String.format(url, args));
        return body == null ? this.asUser(request, user) : this.asUser(request, user).content(body);
    }

    protected MockHttpServletRequestBuilder putAs(AppUser user, String url, String body, Object... args) {
        MockHttpServletRequestBuilder request = put(String.format(url, args));
        return body == null ? this.asUser(request, user) : this.asUser(request, user).content(body);
    }

    protected MockHttpServletRequestBuilder deleteAs(AppUser user, String url, Object... args) {
        return this.asUser(delete(String.format(url, args)), user);
    }

}
