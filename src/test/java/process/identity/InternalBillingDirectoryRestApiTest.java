package process.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What billing-service asks Core (MIG-88/89): the workspaces it bills, their names, the facts the nightly
 * measurement counts (seats, topics in use) and who a user id is. Identity stays in process until it is
 * extracted; Billing reads it here, with the shared service token, and nothing else admits it.
 */
class InternalBillingDirectoryRestApiTest {

    private static final String TOKEN = "t0ken";

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SourceTaskTypeRepository taskTypes = mock(SourceTaskTypeRepository.class);
    private final UserNameResolver names = mock(UserNameResolver.class);
    private final InternalBillingDirectoryRestApi api = new InternalBillingDirectoryRestApi(this.tenants, this.users, this.taskTypes, this.names, TOKEN);

    private static Tenant tenant(long id, String name, TenantStatus status) {
        Tenant t = new Tenant();
        t.setTenantId(id);
        t.setTenantName(name);
        t.setStatus(status);
        return t;
    }

    @Test
    void withoutTheServiceTokenNothingIsAnswered() {
        assertThat(this.api.tenants(null).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.tenants("wrong").getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.usageFacts(" ", 2905L).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.userNames("wrong", Collections.singletonMap("ids", Arrays.asList(1, 2))).getStatusCodeValue()).isEqualTo(401);
        verifyNoInteractions(this.tenants, this.users, this.taskTypes, this.names);
    }

    @Test
    void anUnsetTokenAdmitsNobody() {
        InternalBillingDirectoryRestApi unset = new InternalBillingDirectoryRestApi(this.tenants, this.users, this.taskTypes, this.names, " ");
        assertThat(unset.tenants("").getStatusCodeValue()).isEqualTo(401);
        assertThat(unset.tenants(" ").getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWorkspacesBillingKnowsAreEveryOneNotDeletedWithItsNameAndStatus() {
        when(this.tenants.findByStatusNotOrderByTenantIdDesc(TenantStatus.Delete))
            .thenReturn(Arrays.asList(tenant(2905, "MedAxis Care Network", TenantStatus.Active), tenant(2901, "CareBridge", TenantStatus.Inactive)));

        ResponseEntity<?> answer = this.api.tenants(TOKEN);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) answer.getBody();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("tenantId", 2905L).containsEntry("tenantName", "MedAxis Care Network").containsEntry("status", "Active");
        assertThat(rows.get(1)).containsEntry("status", "Inactive");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNightsFactsAreSeatsThatAreNotDeletedAndTopicsInUse() {
        when(this.users.countByTenantIdAndStatusNot(2905L, Status.Delete)).thenReturn(14L);
        when(this.taskTypes.countTopicsInUse(2905L)).thenReturn(3L);

        Map<String, Object> facts = (Map<String, Object>) this.api.usageFacts(TOKEN, 2905L).getBody();

        assertThat(facts).containsEntry("tenantId", 2905L).containsEntry("seats", 14L).containsEntry("topicsInUse", 3L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void userNamesAreAnsweredForTheIdsAsked() {
        Map<Long, String> resolved = new HashMap<>();
        resolved.put(7L, "Daniel Carter");
        when(this.names.namesFor(any())).thenReturn(resolved);

        Map<String, String> answer = (Map<String, String>) this.api.userNames(TOKEN, Collections.singletonMap("ids", Arrays.asList(7, "x", 8))).getBody();

        assertThat(answer).containsExactly(entry("7", "Daniel Carter"));
    }
}
