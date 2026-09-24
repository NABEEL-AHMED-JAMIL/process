package process.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import process.model.enums.Status;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-192: POST /internal/users/resolve, the batch read every service uses to put a person's name
 * on the work they did. Five columns and no more -- never a password, a phone or a page grant.
 */
class InternalUserDirectoryRestApiTest {

    private static final String TOKEN = "t0ken";
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final InternalUserDirectoryRestApi api = new InternalUserDirectoryRestApi(this.users, TOKEN);

    private static AppUser user(long id, long tenant, String username, String fullName) {
        AppUser u = new AppUser();
        u.setAppUserId(id);
        u.setTenantId(tenant);
        u.setUsername(username);
        u.setFullName(fullName);
        u.setStatus(Status.Active);
        u.setPassword("$2a$10$secret-hash");
        u.setPhoneNumber("+1 555 0100");
        return u;
    }

    @Test
    void withoutTheServiceTokenNothingIsAnswered() {
        assertThat(this.api.resolve(null, Collections.singletonMap("ids", Arrays.asList(1))).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.resolve("wrong", Collections.singletonMap("ids", Arrays.asList(1))).getStatusCodeValue()).isEqualTo(401);
        verifyNoInteractions(this.users);
    }

    @Test
    @SuppressWarnings("unchecked")
    void answersTheFiveColumnProjectionForTheIdsAskedAndNothingElse() {
        when(this.users.findAllByIdAcrossTenants(any())).thenReturn(Arrays.asList(user(7, 2901, "daniel@a.example", "Daniel Carter")));

        ResponseEntity<?> answer = this.api.resolve(TOKEN, Collections.singletonMap("ids", Arrays.asList(7, "x", 8)));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) answer.getBody();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsOnlyKeys("appUserId", "tenantId", "username", "fullName", "status")
            .containsEntry("appUserId", 7L).containsEntry("tenantId", 2901L).containsEntry("username", "daniel@a.example")
            .containsEntry("fullName", "Daniel Carter").containsEntry("status", "Active");
    }

    @Test
    void anEmptyOrMissingListAsksTheDatabaseNothing() {
        assertThat((List<?>) this.api.resolve(TOKEN, Collections.singletonMap("ids", Collections.emptyList())).getBody()).isEmpty();
        assertThat((List<?>) this.api.resolve(TOKEN, Collections.emptyMap()).getBody()).isEmpty();
        verifyNoInteractions(this.users);
    }
}
