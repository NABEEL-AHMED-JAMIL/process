package process.identity;

import org.junit.jupiter.api.Test;
import process.model.enums.Status;
import process.model.repository.AppUserRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What billing-service asks Core (MIG-88/89): the facts the nightly measurement counts (seats, topics in use),
 * with the shared service token, and nothing else admits it. Workspaces and names come from identity-service.
 */
class InternalBillingDirectoryRestApiTest {

    private static final String TOKEN = "t0ken";

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SourceTaskTypeRepository taskTypes = mock(SourceTaskTypeRepository.class);
    private final InternalBillingDirectoryRestApi api = new InternalBillingDirectoryRestApi(TestIdentity.over(this.users, this.tenants), this.taskTypes, TOKEN);

    @Test
    void withoutTheServiceTokenNothingIsAnswered() {
        assertThat(this.api.usageFacts(null, 2905L).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.usageFacts("wrong", 2905L).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.usageFacts(" ", 2905L).getStatusCodeValue()).isEqualTo(401);
        verifyNoInteractions(this.tenants, this.users, this.taskTypes);
    }

    @Test
    void anUnsetTokenAdmitsNobody() {
        InternalBillingDirectoryRestApi unset = new InternalBillingDirectoryRestApi(TestIdentity.over(this.users, this.tenants), this.taskTypes, " ");
        assertThat(unset.usageFacts("", 2905L).getStatusCodeValue()).isEqualTo(401);
        assertThat(unset.usageFacts(" ", 2905L).getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theNightsFactsAreSeatsThatAreNotDeletedAndTopicsInUse() {
        when(this.users.countByTenantIdAndStatusNot(2905L, Status.Delete)).thenReturn(14L);
        when(this.taskTypes.countTopicsInUse(2905L)).thenReturn(3L);

        Map<String, Object> facts = (Map<String, Object>) this.api.usageFacts(TOKEN, 2905L).getBody();

        assertThat(facts).containsEntry("tenantId", 2905L).containsEntry("seats", 14L).containsEntry("topicsInUse", 3L);
    }
}
