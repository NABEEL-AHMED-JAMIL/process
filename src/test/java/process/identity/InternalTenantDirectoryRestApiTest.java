package process.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import process.model.enums.TenantStatus;
import process.model.pojo.Tenant;
import process.model.repository.TenantRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-192: POST /internal/tenants/resolve, the batch read for workspace names -- the tenants
 * counterpart of /internal/users/resolve, for services that show a workspace they do not own.
 * Three columns, one query however many ids, and a deleted workspace still answers with its name
 * and status, because history keeps pointing at it. POST, as every /internal lookup is.
 */
class InternalTenantDirectoryRestApiTest {

    private static final String TOKEN = "t0ken";
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final InternalTenantDirectoryRestApi api = new InternalTenantDirectoryRestApi(TestIdentity.over(null, this.tenants), TOKEN);

    private static Tenant tenant(long id, String name, TenantStatus status) {
        Tenant t = new Tenant();
        t.setTenantId(id);
        t.setTenantName(name);
        t.setStatus(status);
        return t;
    }

    @Test
    void withoutTheServiceTokenNothingIsAnswered() {
        assertThat(this.api.resolve(null, Collections.singletonMap("ids", Arrays.asList(1))).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.resolve("wrong", Collections.singletonMap("ids", Arrays.asList(1))).getStatusCodeValue()).isEqualTo(401);
        verifyNoInteractions(this.tenants);
    }

    @Test
    @SuppressWarnings("unchecked")
    void answersThreeColumnsForTheIdsAskedInOneQuery() {
        when(this.tenants.findAllById(any())).thenReturn(Arrays.asList(
            tenant(2901, "Acme Health", TenantStatus.Active), tenant(2905, "Old Pilot", TenantStatus.Delete)));

        ResponseEntity<?> answer = this.api.resolve(TOKEN, Collections.singletonMap("ids", Arrays.asList(2901, "x", 2905, 2901)));

        List<Map<String, Object>> rows = (List<Map<String, Object>>) answer.getBody();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsOnlyKeys("tenantId", "tenantName", "status")
            .containsEntry("tenantId", 2901L).containsEntry("tenantName", "Acme Health").containsEntry("status", "Active");
        assertThat(rows.get(1)).containsEntry("tenantName", "Old Pilot").containsEntry("status", "Delete");
        verify(this.tenants, times(1)).findAllById(anyIterable());
    }

    @Test
    void noIdsIsAnEmptyAnswerAndNoQuery() {
        assertThat(this.api.resolve(TOKEN, Collections.singletonMap("ids", Collections.emptyList())).getBody())
            .isEqualTo(Collections.emptyList());
        assertThat(this.api.resolve(TOKEN, null).getBody()).isEqualTo(Collections.emptyList());
        verifyNoInteractions(this.tenants);
    }

    /** A batch, not a bulk export: a request for more than 500 workspaces is refused. */
    @Test
    void aBatchIsBounded() {
        Set<Integer> many = new TreeSet<>();
        for (int i = 1; i <= 501; i++) {
            many.add(i);
        }
        assertThat(this.api.resolve(TOKEN, Collections.singletonMap("ids", new ArrayList<>(many))).getStatusCodeValue())
            .isEqualTo(400);
        verifyNoInteractions(this.tenants);
    }
}
