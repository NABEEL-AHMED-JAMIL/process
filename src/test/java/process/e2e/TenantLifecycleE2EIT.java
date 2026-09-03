package process.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import process.model.enums.TenantStatus;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.util.ProcessUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tenant register, end to end.
 *
 * A tenant is the boundary every other rule in this application is drawn around, so who may move
 * that boundary is the first thing worth proving over a real request. The owner's rule is narrow:
 * only a PLATFORM_ADMIN administers tenants. A TENANT_ADMIN administers the users of its own
 * company, which is a different thing entirely -- it does not extend to the company record itself,
 * and it certainly does not extend to anybody else's.
 *
 * The second half of the suite is the other side of that coin, and the reason the refusals below
 * are not the whole story: a tenant's own people must still be able to SEE the tenant they belong
 * to. Everything the console renders -- the name in the header, the "your account is suspended"
 * banner -- comes from what /appUser.json/me hands back. If the refusals were achieved by hiding
 * the tenant altogether the console would go blank, so both halves have to hold at once.
 *
 * @author Nabeel Ahmed
 * */
class TenantLifecycleE2EIT extends E2ESupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- what a PLATFORM_ADMIN may do ---------------------------------------------------------

    @Test
    void aPlatformAdminCanListTenants() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        Tenant existing = this.newTenant("listed");

        JsonNode response = this.okJson(this.getAs(platformAdmin, "/tenant.json/listTenants"));

        assertEquals(ProcessUtil.SUCCESS, response.get("status").asText(), response.toString());
        assertTrue(response.get("data").isArray(), "the listing must be an array of tenants");
        // A platform admin belongs to no tenant, so the per-tenant filter is off for it and the
        // listing reaches every company, not merely the ones it happens to have created.
        assertNotNull(this.tenantFromListing(platformAdmin, existing.getTenantId()),
            "a platform admin must see a tenant it did not create");
    }

    @Test
    void aTenantCreatedThroughTheApiComesBackFromTheListingWithTheValuesThatWereSent() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        String name = "E2E Created " + this.unique();
        String code = "e2e-created-" + this.unique();

        JsonNode created = this.okJson(this.postAs(platformAdmin, "/tenant.json/addTenant",
            this.tenantBody(null, name, code, TenantStatus.Active)));
        assertEquals(ProcessUtil.SUCCESS, created.get("status").asText(), created.toString());

        JsonNode listed = this.tenantFromListing(platformAdmin, created.get("data").get("tenantId").asLong());
        assertNotNull(listed, "a tenant that was just created must appear in the listing");
        assertEquals(name, listed.get("tenantName").asText());
        assertEquals(code, listed.get("tenantCode").asText());
        assertEquals(TenantStatus.Active.name(), listed.get("status").asText());
        // Other parts of the application address a tenant by uuid rather than by its row id, so a
        // tenant that reached the register without one would be unreferenceable.
        assertTrue(listed.hasNonNull("uuid") && !listed.get("uuid").asText().isEmpty(),
            "a created tenant must be given a uuid");
    }

    @Test
    void aPlatformAdminCanRenameATenantAndTheListingShowsTheNewValues() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        long tenantId = this.createTenant(platformAdmin, "E2E Before " + this.unique(),
            "e2e-before-" + this.unique());
        String newName = "E2E After " + this.unique();
        String newCode = "e2e-after-" + this.unique();

        JsonNode updated = this.okJson(this.putAs(platformAdmin, "/tenant.json/updateTenant",
            this.tenantBody(tenantId, newName, newCode, null)));
        assertEquals(ProcessUtil.SUCCESS, updated.get("status").asText(), updated.toString());

        JsonNode listed = this.tenantFromListing(platformAdmin, tenantId);
        assertNotNull(listed);
        assertEquals(newName, listed.get("tenantName").asText());
        assertEquals(newCode, listed.get("tenantCode").asText());
    }

    @Test
    void aPlatformAdminCanChangeATenantStatusAndTheListingShowsIt() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        long tenantId = this.createTenant(platformAdmin, "E2E Status " + this.unique(),
            "e2e-status-" + this.unique());

        JsonNode changed = this.okJson(this.putAs(platformAdmin, "/tenant.json/changeTenantStatus",
            this.tenantBody(tenantId, null, null, TenantStatus.Suspended)));
        assertEquals(ProcessUtil.SUCCESS, changed.get("status").asText(), changed.toString());

        JsonNode listed = this.tenantFromListing(platformAdmin, tenantId);
        assertNotNull(listed, "suspending a tenant must not remove it from the register");
        assertEquals(TenantStatus.Suspended.name(), listed.get("status").asText());
    }

    @Test
    void aTenantMovedToDeleteDropsOutOfTheListing() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        long tenantId = this.createTenant(platformAdmin, "E2E Deleted " + this.unique(),
            "e2e-deleted-" + this.unique());

        JsonNode deleted = this.okJson(this.putAs(platformAdmin, "/tenant.json/changeTenantStatus",
            this.tenantBody(tenantId, null, null, TenantStatus.Delete)));
        assertEquals(ProcessUtil.SUCCESS, deleted.get("status").asText(), deleted.toString());

        // Deletion here is a status, not a DELETE statement -- the row stays so the jobs and files
        // that point at it still resolve. The register is what must stop offering it.
        assertNull(this.tenantFromListing(platformAdmin, tenantId),
            "a deleted tenant must no longer be offered by the listing");
    }

    @Test
    void aTenantCodeAlreadyInUseIsRefusedWhateverItsCasing() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        String code = "e2e-duplicate-" + this.unique();
        this.createTenant(platformAdmin, "E2E First " + this.unique(), code);

        // The code is what the rest of the estate keys a company by, so two companies holding one
        // code would make routing ambiguous. Casing must not be a way around that.
        JsonNode second = this.okJson(this.postAs(platformAdmin, "/tenant.json/addTenant",
            this.tenantBody(null, "E2E Second " + this.unique(), code.toUpperCase(), TenantStatus.Active)));

        assertEquals(ProcessUtil.ERROR, second.get("status").asText(),
            "a duplicate tenant code must be refused: " + second.toString());
        // addTenant refuses a missing name and a missing code with an ERROR too, so a bare status
        // check would have been satisfied by a payload this test never meant to send. The refusal
        // has to name the code -- and the code it names is the normalised, lower-case one, which
        // is the whole of what "whatever its casing" means here.
        assertTrue(second.get("message").asText().contains(code),
            "the refusal must be the duplicate-code one, naming the code that collided: " + second.toString());
    }

    // ---- what a TENANT_ADMIN may not do -------------------------------------------------------

    @Test
    void aTenantAdminIsRefusedTheTenantListing() throws Exception {
        AppUser tenantAdmin = this.newUser(UserRole.TENANT_ADMIN, this.newTenant("refused"));

        this.mvc.perform(this.getAs(tenantAdmin, "/tenant.json/listTenants"))
            .andExpect(status().isForbidden());
    }

    @Test
    void aTenantAdminIsRefusedCreatingATenant() throws Exception {
        AppUser tenantAdmin = this.newUser(UserRole.TENANT_ADMIN, this.newTenant("refused"));

        this.mvc.perform(this.postAs(tenantAdmin, "/tenant.json/addTenant",
                this.tenantBody(null, "E2E Sneak " + this.unique(), "e2e-sneak-" + this.unique(),
                    TenantStatus.Active)))
            .andExpect(status().isForbidden());
    }

    @Test
    void aTenantAdminIsRefusedRenamingAnotherCompanysTenantAndTheNameSurvives() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        Tenant victim = this.newTenant("victim");
        AppUser intruder = this.newUser(UserRole.TENANT_ADMIN, this.newTenant("intruder"));
        String originalName = victim.getTenantName();

        this.mvc.perform(this.putAs(intruder, "/tenant.json/updateTenant",
                this.tenantBody(victim.getTenantId(), "Seized " + this.unique(),
                    "seized-" + this.unique(), null)))
            .andExpect(status().isForbidden());

        // A 403 that still wrote the row would be no protection at all, so the refusal is checked
        // from the outside: the register must show the name the other company never agreed to lose.
        JsonNode listed = this.tenantFromListing(platformAdmin, victim.getTenantId());
        assertNotNull(listed);
        assertEquals(originalName, listed.get("tenantName").asText());
    }

    @Test
    void aTenantAdminIsRefusedAdministeringEvenItsOwnTenant() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        Tenant own = this.newTenant("own");
        AppUser tenantAdmin = this.newUser(UserRole.TENANT_ADMIN, own);
        String originalName = own.getTenantName();

        // The owner's rule gives a tenant admin its own USERS, not its own company record. Renaming
        // the company changes what every other tenant sees in shared listings, so it stays with the
        // platform. This is the case most likely to be got wrong, because "his company account"
        // reads as though it included the account itself.
        this.mvc.perform(this.putAs(tenantAdmin, "/tenant.json/updateTenant",
                this.tenantBody(own.getTenantId(), "Self Renamed " + this.unique(),
                    "self-renamed-" + this.unique(), null)))
            .andExpect(status().isForbidden());

        JsonNode listed = this.tenantFromListing(platformAdmin, own.getTenantId());
        assertNotNull(listed);
        assertEquals(originalName, listed.get("tenantName").asText());
    }

    @Test
    void aTenantAdminIsRefusedChangingATenantStatusAndTheStatusSurvives() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        Tenant own = this.newTenant("status");
        AppUser tenantAdmin = this.newUser(UserRole.TENANT_ADMIN, own);

        // Reactivating a suspended company from inside it would undo the only lever the platform
        // has over a tenant that has stopped paying or has been shut off for cause.
        this.mvc.perform(this.putAs(tenantAdmin, "/tenant.json/changeTenantStatus",
                this.tenantBody(own.getTenantId(), null, null, TenantStatus.Delete)))
            .andExpect(status().isForbidden());

        JsonNode listed = this.tenantFromListing(platformAdmin, own.getTenantId());
        assertNotNull(listed, "a tenant admin must not be able to delete its own company");
        assertEquals(TenantStatus.Active.name(), listed.get("status").asText());
    }

    // ---- what a TENANT_USER may not do --------------------------------------------------------

    @Test
    void aTenantUserIsRefusedTheTenantListing() throws Exception {
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, this.newTenant("plain"));

        this.mvc.perform(this.getAs(tenantUser, "/tenant.json/listTenants"))
            .andExpect(status().isForbidden());
    }

    @Test
    void aTenantUserIsRefusedCreatingATenant() throws Exception {
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, this.newTenant("plain"));

        this.mvc.perform(this.postAs(tenantUser, "/tenant.json/addTenant",
                this.tenantBody(null, "E2E Plain " + this.unique(), "e2e-plain-" + this.unique(),
                    TenantStatus.Active)))
            .andExpect(status().isForbidden());
    }

    @Test
    void aTenantUserIsRefusedUpdatingATenant() throws Exception {
        Tenant own = this.newTenant("plain");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, own);

        this.mvc.perform(this.putAs(tenantUser, "/tenant.json/updateTenant",
                this.tenantBody(own.getTenantId(), "E2E Plain " + this.unique(),
                    "e2e-plain-" + this.unique(), null)))
            .andExpect(status().isForbidden());
    }

    @Test
    void aTenantUserIsRefusedChangingATenantStatus() throws Exception {
        Tenant own = this.newTenant("plain");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, own);

        this.mvc.perform(this.putAs(tenantUser, "/tenant.json/changeTenantStatus",
                this.tenantBody(own.getTenantId(), null, null, TenantStatus.Suspended)))
            .andExpect(status().isForbidden());
    }

    // ---- what everybody may still see ---------------------------------------------------------

    @Test
    void aTenantAdminStillSeesItsOwnTenantOnItsProfile() throws Exception {
        Tenant own = this.newTenant("visible");
        AppUser tenantAdmin = this.newUser(UserRole.TENANT_ADMIN, own);

        JsonNode profile = this.okJson(this.getAs(tenantAdmin, "/appUser.json/me")).get("data");

        assertEquals(own.getTenantId().longValue(), profile.get("tenantId").asLong());
        assertEquals(own.getTenantName(), profile.get("tenantName").asText());
        assertTrue(profile.get("tenantActive").asBoolean(), "an active tenant must read as active");
    }

    @Test
    void aTenantUserStillSeesItsOwnTenantOnItsProfile() throws Exception {
        Tenant own = this.newTenant("visible");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, own);

        JsonNode profile = this.okJson(this.getAs(tenantUser, "/appUser.json/me")).get("data");

        assertEquals(own.getTenantId().longValue(), profile.get("tenantId").asLong());
        assertEquals(own.getTenantName(), profile.get("tenantName").asText());
    }

    @Test
    void aTenantAdminRefusedTheTenantRegisterStillReachesItsOwnDashboard() throws Exception {
        Tenant own = this.newTenant("dashboard");
        AppUser tenantAdmin = this.newUser(UserRole.TENANT_ADMIN, own);

        this.mvc.perform(this.getAs(tenantAdmin, "/tenant.json/listTenants"))
            .andExpect(status().isForbidden());

        // The two assertions belong in one test because the point is the contrast: being shut out
        // of tenant administration must not shut a company out of its own console.
        this.mvc.perform(this.getAs(tenantAdmin, "/dashboard.json/jobStatusStatistics"))
            .andExpect(status().isOk());
    }

    @Test
    void suspendingATenantShowsUpOnTheProfileOfItsOwnUsers() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        Tenant own = this.newTenant("suspended");
        AppUser tenantUser = this.newUser(UserRole.TENANT_USER, own);

        JsonNode before = this.okJson(this.getAs(tenantUser, "/appUser.json/me")).get("data");
        assertTrue(before.get("tenantActive").asBoolean());

        this.okJson(this.putAs(platformAdmin, "/tenant.json/changeTenantStatus",
            this.tenantBody(own.getTenantId(), null, null, TenantStatus.Suspended)));

        // The whole chain in one hop: a decision only the platform can take, landing on the screen
        // of somebody who cannot see the register it was taken in. The console reads this flag to
        // put up its suspended banner, so if it did not follow the register the banner never shows.
        JsonNode after = this.okJson(this.getAs(tenantUser, "/appUser.json/me")).get("data");
        assertFalse(after.get("tenantActive").asBoolean(),
            "a suspended tenant must stop reading as active for its own users");
    }

    @Test
    void aPlatformAdminBelongsToNoTenant() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();

        JsonNode profile = this.okJson(this.getAs(platformAdmin, "/appUser.json/me")).get("data");

        // Belonging nowhere is exactly what lets a platform admin reach everywhere: the per-tenant
        // filter keys off this being absent. A platform admin that acquired a tenant id would
        // quietly start seeing one company's data only.
        assertFalse(profile.hasNonNull("tenantId"), "a platform admin must not carry a tenant id");
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Performs the call, insists on a 200 and hands back the parsed envelope. */
    private JsonNode okJson(MockHttpServletRequestBuilder request) throws Exception {
        return MAPPER.readTree(this.mvc.perform(request)
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    /**
     * Builds a TenantDto payload from whichever fields a given call actually carries -- the four
     * endpoints read different subsets of it, and sending the rest anyway would hide which field
     * each one really depends on.
     */
    private String tenantBody(Long tenantId, String tenantName, String tenantCode, TenantStatus status) {
        ObjectNode body = MAPPER.createObjectNode();
        if (tenantId != null) {
            body.put("tenantId", tenantId);
        }
        if (tenantName != null) {
            body.put("tenantName", tenantName);
        }
        if (tenantCode != null) {
            body.put("tenantCode", tenantCode);
        }
        if (status != null) {
            body.put("status", status.name());
        }
        return body.toString();
    }

    /** Creates a tenant the way an operator would, so the tests that follow act on a real one. */
    private long createTenant(AppUser platformAdmin, String name, String code) throws Exception {
        JsonNode created = this.okJson(this.postAs(platformAdmin, "/tenant.json/addTenant",
            this.tenantBody(null, name, code, TenantStatus.Active)));
        assertEquals(ProcessUtil.SUCCESS, created.get("status").asText(), created.toString());
        return created.get("data").get("tenantId").asLong();
    }

    /** The register as an operator reads it, or null when the tenant is no longer offered. */
    private JsonNode tenantFromListing(AppUser platformAdmin, long tenantId) throws Exception {
        JsonNode response = this.okJson(this.getAs(platformAdmin, "/tenant.json/listTenants"));
        assertEquals(ProcessUtil.SUCCESS, response.get("status").asText(), response.toString());
        for (JsonNode tenant : response.get("data")) {
            if (tenant.get("tenantId").asLong() == tenantId) {
                return tenant;
            }
        }
        return null;
    }

}
