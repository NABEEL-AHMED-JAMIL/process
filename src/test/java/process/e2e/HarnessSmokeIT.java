package process.e2e;

import org.junit.jupiter.api.Test;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the harness itself works: a real token reaches a real endpoint through the real chain. */
class HarnessSmokeIT extends E2ESupport {

    @Test
    void aSignedInUserReachesTheirOwnProfile() throws Exception {
        Tenant tenant = this.newTenant("smoke");
        AppUser user = this.newUser(UserRole.TENANT_USER, tenant);

        this.mvc.perform(this.getAs(user, "/appUser.json/me"))
            .andExpect(status().isOk());
    }

    @Test
    void noTokenIsRefused() throws Exception {
        this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/appUser.json/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void aTenantUserIsRefusedAPlatformAdminEndpoint() throws Exception {
        Tenant tenant = this.newTenant("smoke");
        AppUser user = this.newUser(UserRole.TENANT_USER, tenant);

        this.mvc.perform(this.getAs(user, "/tenant.json/listTenants"))
            .andExpect(status().isForbidden());
    }
}
