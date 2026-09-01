package process.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may do what to whom, asked of the running application rather than of a service.
 *
 * The rule this suite exists for is the owner's: a platform admin acts on everybody, a tenant
 * admin staffs its own workspace and reaches nothing in anybody else's, and an ordinary user
 * has only itself. That rule is spread across three layers -- the role annotations on the
 * controller, the tenant scoping in the service, and the token the filter chain builds -- and
 * each layer can be right on its own while the three together leave a door open. Only a real
 * request passes through all three, which is why these cases go over HTTP.
 *
 * Refusals are asserted through {@link #assertRefused}, which accepts either shape the
 * application uses: a 403 from the filter chain when a role rule decides, or a 200 carrying an
 * ERROR body when the ownership rule decides. Pinning one of the two would turn a rule moving
 * between layers into a failure, and the boundary would not have moved at all. Where a case is
 * about the boundary rather than the wording, it also reads the row back: a refusal that still
 * wrote is not a refusal.
 *
 * @author Nabeel Ahmed
 */
class UserManagementE2EIT extends E2ESupport {

    private static final String LIST_USERS = "/appUser.json/listUsers";
    private static final String ADD_USER = "/appUser.json/addUser";
    private static final String UPDATE_USER = "/appUser.json/updateUser";
    private static final String CHANGE_USER_STATUS = "/appUser.json/changeUserStatus";
    private static final String RESET_PASSWORD = "/appUser.json/resetPassword";
    private static final String ME = "/appUser.json/me";
    private static final String UPDATE_OWN_PROFILE = "/appUser.json/updateOwnProfile";

    private static final String SUCCESS = "SUCCESS";
    private static final String ERROR = "ERROR";

    /** Past anything the app_user sequence has issued, so no row can accidentally answer for it. */
    private static final long ABSENT_USER_ID = 9_999_999_999L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- a tenant admin inside its own company ---------------------------------------------

    @Test
    void aTenantAdminCreatesATenantUserInItsOwnCompany() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        String username = this.freshUsername();

        JsonNode created = this.successBody(this.postAs(adminA, ADD_USER,
            this.newUserBody(username, UserRole.TENANT_USER, null)))
            .path("data");

        // The workspace is taken from the token, never from the form -- an admin creating a user
        // is always creating it in its own company.
        assertThat(created.path("tenantId").asLong()).isEqualTo(companyA.getTenantId());
        assertThat(created.path("userRole").asText()).isEqualTo(UserRole.TENANT_USER.name());
        assertThat(created.path("status").asText()).isEqualTo(Status.Active.name());
        Optional<AppUser> stored = this.appUserRepository.findByUsernameAndStatusNot(username, Status.Delete);
        assertThat(stored).isPresent();
        assertThat(stored.get().getTenantId()).isEqualTo(companyA.getTenantId());
    }

    @Test
    void aTenantAdminEditsATenantUserInItsOwnCompany() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        long targetId = this.createTenantUser(adminA, null);

        this.successBody(this.putAs(adminA, UPDATE_USER, this.updateUserBody(targetId, "Renamed By Own Admin", null)));

        assertThat(this.appUserRepository.findById(targetId).get().getFullName())
            .isEqualTo("Renamed By Own Admin");
    }

    @Test
    void aTenantAdminResetsThePasswordOfATenantUserInItsOwnCompany() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        long targetId = this.createTenantUser(adminA, null);
        String hashBefore = this.appUserRepository.findById(targetId).get().getPassword();

        this.successBody(this.resetPasswordRequest(adminA, targetId));

        AppUser target = this.appUserRepository.findById(targetId).get();
        assertThat(target.getPassword()).isNotEqualTo(hashBefore);
        // An administrator who typed the password knows it, so the account owes a change before
        // it is the user's alone again.
        assertThat(target.isMustChangePassword()).isTrue();
    }

    @Test
    void aTenantAdminDeactivatesATenantUserInItsOwnCompany() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        long targetId = this.createTenantUser(adminA, null);

        this.successBody(this.putAs(adminA, CHANGE_USER_STATUS, this.statusBody(targetId, Status.Inactive)));

        assertThat(this.appUserRepository.findById(targetId).get().getStatus()).isEqualTo(Status.Inactive);
    }

    // ---- the company boundary, tested where only the boundary can refuse --------------------
    //
    // Every target below is an ORDINARY user of the other company. Against another company's
    // admin the role rule would refuse too, and a passing test would not say which of the two
    // rules did the work; against a tenant user, nothing but the boundary stands in the way.

    @Test
    void aTenantAdminCannotEditAnOrdinaryUserOfAnotherCompany() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        Tenant companyB = this.newTenant("company-b");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        AppUser userB = this.newUser(UserRole.TENANT_USER, companyB);
        String nameBefore = userB.getFullName();

        this.assertRefused(this.putAs(adminA, UPDATE_USER,
            this.updateUserBody(userB.getAppUserId(), "Renamed By A Stranger", null)));

        assertThat(this.appUserRepository.findById(userB.getAppUserId()).get().getFullName())
            .isEqualTo(nameBefore);
    }

    @Test
    void aTenantAdminCannotResetThePasswordOfAnOrdinaryUserOfAnotherCompany() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        Tenant companyB = this.newTenant("company-b");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        AppUser userB = this.newUser(UserRole.TENANT_USER, companyB);
        String hashBefore = userB.getPassword();

        this.assertRefused(this.resetPasswordRequest(adminA, userB.getAppUserId()));

        // The sharpest form of the boundary: a reset that went through would hand one company's
        // administrator a working sign-in for another company's account.
        assertThat(this.appUserRepository.findById(userB.getAppUserId()).get().getPassword())
            .isEqualTo(hashBefore);
    }

    @Test
    void aTenantAdminCannotDeactivateAnOrdinaryUserOfAnotherCompany() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        Tenant companyB = this.newTenant("company-b");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        AppUser userB = this.newUser(UserRole.TENANT_USER, companyB);

        this.assertRefused(this.putAs(adminA, CHANGE_USER_STATUS,
            this.statusBody(userB.getAppUserId(), Status.Inactive)));

        assertThat(this.appUserRepository.findById(userB.getAppUserId()).get().getStatus())
            .isEqualTo(Status.Active);
    }

    @Test
    void aTenantAdminDoesNotSeeAnotherCompanysUsersWhenItListsUsers() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        Tenant companyB = this.newTenant("company-b");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);
        AppUser userB = this.newUser(UserRole.TENANT_USER, companyB);

        Set<Long> listed = this.listedUserIds(adminA);

        // "cannot access them" is not only about writing: the other company's staff must not even
        // be nameable from this screen.
        assertThat(listed).contains(userA.getAppUserId());
        assertThat(listed).doesNotContain(userB.getAppUserId());
    }

    @Test
    void aTenantAdminNamingAnotherCompanyOnACreateDoesNotPlaceTheUserThere() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        Tenant companyB = this.newTenant("company-b");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        String username = this.freshUsername();

        this.call(this.postAs(adminA, ADD_USER, this.newUserBody(username, UserRole.TENANT_USER, companyB.getTenantId())));

        // Refusing and quietly filing the account in the admin's own workspace are both acceptable
        // answers; staffing somebody else's company is the one that is not.
        Optional<AppUser> created = this.appUserRepository.findByUsernameAndStatusNot(username, Status.Delete);
        if (created.isPresent()) {
            assertThat(created.get().getTenantId())
                .as("a tenant admin must not be able to create an account inside another company")
                .isEqualTo(companyA.getTenantId());
        }
    }

    // ---- the line inside a company, and the line above it -----------------------------------

    @Test
    void aTenantAdminCannotManageAPeerAdminOfItsOwnCompany() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        AppUser peerAdmin = this.newUser(UserRole.TENANT_ADMIN, companyA);
        String nameBefore = peerAdmin.getFullName();
        String hashBefore = peerAdmin.getPassword();

        // Sharing a workspace is not the same as outranking somebody in it. A second administrator
        // is a second set of keys to everything the workspace holds, so whoever holds one is the
        // platform's business -- otherwise two-person control means nothing.
        this.assertRefused(this.putAs(adminA, UPDATE_USER,
            this.updateUserBody(peerAdmin.getAppUserId(), "Renamed By A Peer", null)));
        this.assertRefused(this.resetPasswordRequest(adminA, peerAdmin.getAppUserId()));
        this.assertRefused(this.putAs(adminA, CHANGE_USER_STATUS,
            this.statusBody(peerAdmin.getAppUserId(), Status.Inactive)));

        AppUser after = this.appUserRepository.findById(peerAdmin.getAppUserId()).get();
        assertThat(after.getFullName()).isEqualTo(nameBefore);
        assertThat(after.getPassword()).isEqualTo(hashBefore);
        assertThat(after.getStatus()).isEqualTo(Status.Active);
    }

    @Test
    void aTenantAdminCannotManageAPlatformAdmin() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        AppUser platformAdmin = this.newPlatformAdmin();
        String nameBefore = platformAdmin.getFullName();
        String hashBefore = platformAdmin.getPassword();

        this.assertRefused(this.putAs(adminA, UPDATE_USER,
            this.updateUserBody(platformAdmin.getAppUserId(), "Renamed By A Tenant Admin", null)));
        this.assertRefused(this.resetPasswordRequest(adminA, platformAdmin.getAppUserId()));
        this.assertRefused(this.putAs(adminA, CHANGE_USER_STATUS,
            this.statusBody(platformAdmin.getAppUserId(), Status.Inactive)));

        AppUser after = this.appUserRepository.findById(platformAdmin.getAppUserId()).get();
        assertThat(after.getFullName()).isEqualTo(nameBefore);
        assertThat(after.getPassword()).isEqualTo(hashBefore);
        assertThat(after.getStatus()).isEqualTo(Status.Active);
    }

    // ---- nobody grants themselves a colleague their own level -------------------------------

    @Test
    void aTenantAdminCannotCreateATenantAdmin() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        String username = this.freshUsername();

        this.assertRefused(this.postAs(adminA, ADD_USER,
            this.newUserBody(username, UserRole.TENANT_ADMIN, null)));

        assertThat(this.appUserRepository.findByUsernameAndStatusNot(username, Status.Delete)).isEmpty();
    }

    @Test
    void aTenantAdminCannotCreateAPlatformAdmin() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        String username = this.freshUsername();

        this.assertRefused(this.postAs(adminA, ADD_USER,
            this.newUserBody(username, UserRole.PLATFORM_ADMIN, null)));

        assertThat(this.appUserRepository.findByUsernameAndStatusNot(username, Status.Delete)).isEmpty();
    }

    @Test
    void aTenantAdminCannotPromoteATenantUserToTenantAdmin() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        long targetId = this.createTenantUser(adminA, null);

        // Creating one and promoting one into it are the same grant wearing different clothes;
        // closing only the first door leaves the second wide open.
        this.assertRefused(this.putAs(adminA, UPDATE_USER,
            this.updateUserBody(targetId, "Promoted", UserRole.TENANT_ADMIN)));

        assertThat(this.appUserRepository.findById(targetId).get().getUserRole())
            .isEqualTo(UserRole.TENANT_USER);
    }

    @Test
    void aTenantAdminCannotPromoteATenantUserToPlatformAdmin() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        long targetId = this.createTenantUser(adminA, null);

        this.assertRefused(this.putAs(adminA, UPDATE_USER,
            this.updateUserBody(targetId, "Promoted", UserRole.PLATFORM_ADMIN)));

        AppUser after = this.appUserRepository.findById(targetId).get();
        assertThat(after.getUserRole()).isEqualTo(UserRole.TENANT_USER);
        // A platform admin belongs to no company; a promotion that half-succeeded would have cut
        // the account loose from company A as well.
        assertThat(after.getTenantId()).isEqualTo(companyA.getTenantId());
    }

    // ---- a platform admin, everywhere, on every role -----------------------------------------

    @Test
    void aPlatformAdminCreatesAUserInAnyCompany() throws Exception {
        Tenant companyB = this.newTenant("company-b");
        AppUser platformAdmin = this.newPlatformAdmin();
        String username = this.freshUsername();

        JsonNode created = this.successBody(this.postAs(platformAdmin, ADD_USER,
            this.newUserBody(username, UserRole.TENANT_USER, companyB.getTenantId())))
            .path("data");

        // Belonging to no company is what lets a platform admin name one.
        assertThat(created.path("tenantId").asLong()).isEqualTo(companyB.getTenantId());
    }

    @Test
    void aPlatformAdminCreatesATenantAdmin() throws Exception {
        Tenant companyB = this.newTenant("company-b");
        AppUser platformAdmin = this.newPlatformAdmin();
        String username = this.freshUsername();

        JsonNode created = this.successBody(this.postAs(platformAdmin, ADD_USER,
            this.newUserBody(username, UserRole.TENANT_ADMIN, companyB.getTenantId())))
            .path("data");

        assertThat(created.path("userRole").asText()).isEqualTo(UserRole.TENANT_ADMIN.name());
        assertThat(created.path("tenantId").asLong()).isEqualTo(companyB.getTenantId());
    }

    @Test
    void aPlatformAdminCreatesAnotherPlatformAdmin() throws Exception {
        AppUser platformAdmin = this.newPlatformAdmin();
        String username = this.freshUsername();

        this.successBody(this.postAs(platformAdmin, ADD_USER,
            this.newUserBody(username, UserRole.PLATFORM_ADMIN, null)));

        AppUser created = this.appUserRepository.findByUsernameAndStatusNot(username, Status.Delete).get();
        assertThat(created.getUserRole()).isEqualTo(UserRole.PLATFORM_ADMIN);
        // No company, which is the whole of what the role means here.
        assertThat(created.getTenantId()).isNull();
    }

    @Test
    void aPlatformAdminEditsAUserInAnyCompany() throws Exception {
        Tenant companyB = this.newTenant("company-b");
        AppUser platformAdmin = this.newPlatformAdmin();
        AppUser userB = this.newUser(UserRole.TENANT_USER, companyB);

        this.successBody(this.putAs(platformAdmin, UPDATE_USER,
            this.updateUserBody(userB.getAppUserId(), "Renamed By The Platform", null)));

        assertThat(this.appUserRepository.findById(userB.getAppUserId()).get().getFullName())
            .isEqualTo("Renamed By The Platform");
    }

    @Test
    void aPlatformAdminResetsThePasswordOfATenantAdmin() throws Exception {
        Tenant companyB = this.newTenant("company-b");
        AppUser platformAdmin = this.newPlatformAdmin();
        AppUser adminB = this.newUser(UserRole.TENANT_ADMIN, companyB);
        String hashBefore = adminB.getPassword();

        // The one reset a tenant admin is refused. Somebody has to be able to do it, and this is
        // the role the owner named.
        this.successBody(this.resetPasswordRequest(platformAdmin, adminB.getAppUserId()));

        AppUser after = this.appUserRepository.findById(adminB.getAppUserId()).get();
        assertThat(after.getPassword()).isNotEqualTo(hashBefore);
        assertThat(after.isMustChangePassword()).isTrue();
    }

    @Test
    void aPlatformAdminDeactivatesATenantAdminOfAnyCompany() throws Exception {
        Tenant companyB = this.newTenant("company-b");
        AppUser platformAdmin = this.newPlatformAdmin();
        AppUser adminB = this.newUser(UserRole.TENANT_ADMIN, companyB);

        this.successBody(this.putAs(platformAdmin, CHANGE_USER_STATUS,
            this.statusBody(adminB.getAppUserId(), Status.Inactive)));

        assertThat(this.appUserRepository.findById(adminB.getAppUserId()).get().getStatus())
            .isEqualTo(Status.Inactive);
    }

    @Test
    void aPlatformAdminPromotesATenantUserToTenantAdmin() throws Exception {
        Tenant companyB = this.newTenant("company-b");
        AppUser platformAdmin = this.newPlatformAdmin();
        AppUser userB = this.newUser(UserRole.TENANT_USER, companyB);

        this.successBody(this.putAs(platformAdmin, UPDATE_USER,
            this.updateUserBody(userB.getAppUserId(), "Promoted By The Platform", UserRole.TENANT_ADMIN)));

        AppUser after = this.appUserRepository.findById(userB.getAppUserId()).get();
        assertThat(after.getUserRole()).isEqualTo(UserRole.TENANT_ADMIN);
        assertThat(after.getTenantId()).isEqualTo(companyB.getTenantId());
    }

    @Test
    void aPlatformAdminListsUsersFromEveryCompany() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        Tenant companyB = this.newTenant("company-b");
        AppUser platformAdmin = this.newPlatformAdmin();
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);
        AppUser userB = this.newUser(UserRole.TENANT_USER, companyB);

        Set<Long> listed = this.listedUserIds(platformAdmin);

        assertThat(listed).contains(userA.getAppUserId(), userB.getAppUserId());
    }

    // ---- an ordinary user has itself and nothing else ---------------------------------------

    @Test
    void aTenantUserReadsItsOwnProfile() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);

        JsonNode profile = this.successBody(this.getAs(userA, ME)).path("data");

        // The id comes off the token, so "me" cannot be pointed at anybody else.
        assertThat(profile.path("appUserId").asLong()).isEqualTo(userA.getAppUserId());
        assertThat(profile.path("username").asText()).isEqualTo(userA.getUsername());
    }

    @Test
    void aTenantUserEditsItsOwnProfile() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);

        this.successBody(this.putAs(userA, UPDATE_OWN_PROFILE, this.ownProfileBody("Renamed By Themselves", null)));

        assertThat(this.appUserRepository.findById(userA.getAppUserId()).get().getFullName())
            .isEqualTo("Renamed By Themselves");
    }

    @Test
    void aTenantUserCannotChangeItsOwnRoleThroughItsProfile() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);

        // The profile form is the one write a tenant user is allowed, so it is the obvious place
        // to try posting a fuller payload than the screen offers.
        this.call(this.putAs(userA, UPDATE_OWN_PROFILE,
            this.ownProfileBody("Self Promoted", UserRole.PLATFORM_ADMIN)));

        AppUser after = this.appUserRepository.findById(userA.getAppUserId()).get();
        assertThat(after.getUserRole()).isEqualTo(UserRole.TENANT_USER);
        assertThat(after.getTenantId()).isEqualTo(companyA.getTenantId());
    }

    @Test
    void aTenantUserCannotListUsers() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);

        this.assertRefused(this.getAs(userA, LIST_USERS));
    }

    @Test
    void aTenantUserCannotCreateAUser() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);
        String username = this.freshUsername();

        this.assertRefused(this.postAs(userA, ADD_USER, this.newUserBody(username, UserRole.TENANT_USER, null)));

        assertThat(this.appUserRepository.findByUsernameAndStatusNot(username, Status.Delete)).isEmpty();
    }

    @Test
    void aTenantUserCannotEditAColleague() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);
        AppUser colleague = this.newUser(UserRole.TENANT_USER, companyA);
        String nameBefore = colleague.getFullName();

        this.assertRefused(this.putAs(userA, UPDATE_USER,
            this.updateUserBody(colleague.getAppUserId(), "Renamed By A Colleague", null)));

        assertThat(this.appUserRepository.findById(colleague.getAppUserId()).get().getFullName())
            .isEqualTo(nameBefore);
    }

    @Test
    void aTenantUserCannotResetAColleaguesPassword() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);
        AppUser colleague = this.newUser(UserRole.TENANT_USER, companyA);
        String hashBefore = colleague.getPassword();

        this.assertRefused(this.resetPasswordRequest(userA, colleague.getAppUserId()));

        assertThat(this.appUserRepository.findById(colleague.getAppUserId()).get().getPassword())
            .isEqualTo(hashBefore);
    }

    @Test
    void aTenantUserCannotDeactivateAColleague() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        AppUser userA = this.newUser(UserRole.TENANT_USER, companyA);
        AppUser colleague = this.newUser(UserRole.TENANT_USER, companyA);

        this.assertRefused(this.putAs(userA, CHANGE_USER_STATUS,
            this.statusBody(colleague.getAppUserId(), Status.Inactive)));

        assertThat(this.appUserRepository.findById(colleague.getAppUserId()).get().getStatus())
            .isEqualTo(Status.Active);
    }

    // ---- what a refusal gives away -----------------------------------------------------------

    @Test
    void aRefusalDoesNotRevealWhetherAnAccountInAnotherCompanyExists() throws Exception {
        Tenant companyA = this.newTenant("company-a");
        Tenant companyB = this.newTenant("company-b");
        AppUser adminA = this.newUser(UserRole.TENANT_ADMIN, companyA);
        AppUser userB = this.newUser(UserRole.TENANT_USER, companyB);

        // Refusing an id that exists differently from one that does not turns the endpoint into a
        // directory: walk the ids, keep the ones whose refusal reads differently, and the other
        // company's headcount and account ids fall out of it. The two answers have to be the same
        // sentence.
        assertThat(this.refusalMessageFor(this.putAs(adminA, UPDATE_USER,
            this.updateUserBody(userB.getAppUserId(), "Probe", null))))
            .as("editing a real account in another company must read exactly like editing one that does not exist")
            .isEqualTo(this.refusalMessageFor(this.putAs(adminA, UPDATE_USER,
                this.updateUserBody(ABSENT_USER_ID, "Probe", null))));

        assertThat(this.refusalMessageFor(this.resetPasswordRequest(adminA, userB.getAppUserId())))
            .as("a password reset must not tell one company that another company's account exists")
            .isEqualTo(this.refusalMessageFor(this.resetPasswordRequest(adminA, ABSENT_USER_ID)));

        assertThat(this.refusalMessageFor(this.putAs(adminA, CHANGE_USER_STATUS,
            this.statusBody(userB.getAppUserId(), Status.Inactive))))
            .as("a status change must not tell one company that another company's account exists")
            .isEqualTo(this.refusalMessageFor(this.putAs(adminA, CHANGE_USER_STATUS,
                this.statusBody(ABSENT_USER_ID, Status.Inactive))));
    }

    // ---- plumbing ----------------------------------------------------------------------------

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return this.mvc.perform(request).andReturn();
    }

    /** The body of an answer, insisted upon: an empty response says nothing either way. */
    private JsonNode bodyOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body).as("the endpoint answered with no body at all").isNotEmpty();
        return MAPPER.readTree(body);
    }

    private JsonNode successBody(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = this.call(request);
        assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        JsonNode body = this.bodyOf(result);
        assertThat(body.path("status").asText()).isEqualTo(SUCCESS);
        return body;
    }

    /**
     * Asserts the call did not happen, in whichever of the two shapes the application uses, and
     * hands back the sentence it refused with. Null when the filter chain refused before the
     * request reached anything that could phrase a message -- which by itself discloses nothing.
     */
    private String refusalMessageFor(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = this.call(request);
        if (result.getResponse().getStatus() == HttpStatus.FORBIDDEN.value()) {
            return null;
        }
        assertThat(result.getResponse().getStatus())
            .as("a refusal is either a 403 or a 200 carrying an ERROR body")
            .isEqualTo(HttpStatus.OK.value());
        JsonNode body = this.bodyOf(result);
        assertThat(body.path("status").asText()).isEqualTo(ERROR);
        return body.path("message").asText();
    }

    private void assertRefused(MockHttpServletRequestBuilder request) throws Exception {
        this.refusalMessageFor(request);
    }

    /** The ids a caller can see on the users screen. */
    private Set<Long> listedUserIds(AppUser caller) throws Exception {
        JsonNode data = this.successBody(this.getAs(caller, LIST_USERS)).path("data");
        Set<Long> ids = new HashSet<>();
        for (JsonNode row : data) {
            ids.add(row.path("appUserId").asLong());
        }
        return ids;
    }

    /**
     * An account made by the application rather than written straight into the table, so the
     * cases that go on to edit or deactivate one start from a row the create path produced.
     */
    private long createTenantUser(AppUser actor, Long tenantId) throws Exception {
        JsonNode created = this.successBody(this.postAs(actor, ADD_USER,
            this.newUserBody(this.freshUsername(), UserRole.TENANT_USER, tenantId))).path("data");
        return created.path("appUserId").asLong();
    }

    private String freshUsername() {
        return "e2e-created-" + this.unique() + "@example.test";
    }

    /**
     * A reset request. The replacement is a throwaway string minted per call -- long enough to
     * clear the length rule and never used to sign in with, here or anywhere.
     */
    private MockHttpServletRequestBuilder resetPasswordRequest(AppUser actor, long appUserId) {
        return this.putAs(actor, RESET_PASSWORD,
            String.format("{\"appUserId\":%d,\"password\":\"replaced-%s\"}", appUserId, this.unique()));
    }

    private String newUserBody(String username, UserRole role, Long tenantId) {
        StringBuilder body = new StringBuilder("{");
        body.append("\"username\":\"").append(username).append("\",");
        body.append("\"fullName\":\"E2E Created User\",");
        body.append("\"userRole\":\"").append(role.name()).append("\"");
        if (tenantId != null) {
            body.append(",\"tenantId\":").append(tenantId);
        }
        return body.append("}").toString();
    }

    /** Full name is required by the endpoint, so every edit carries one; role only when asked for. */
    private String updateUserBody(long appUserId, String fullName, UserRole role) {
        StringBuilder body = new StringBuilder("{");
        body.append("\"appUserId\":").append(appUserId).append(",");
        body.append("\"fullName\":\"").append(fullName).append("\"");
        if (role != null) {
            body.append(",\"userRole\":\"").append(role.name()).append("\"");
        }
        return body.append("}").toString();
    }

    private String statusBody(long appUserId, Status status) {
        return String.format("{\"appUserId\":%d,\"status\":\"%s\"}", appUserId, status.name());
    }

    private String ownProfileBody(String fullName, UserRole role) {
        StringBuilder body = new StringBuilder("{");
        body.append("\"fullName\":\"").append(fullName).append("\"");
        if (role != null) {
            body.append(",\"userRole\":\"").append(role.name()).append("\"");
        }
        return body.append("}").toString();
    }

}
