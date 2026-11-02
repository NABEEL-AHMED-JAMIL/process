package process.retention;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import process.model.dto.ResponseDto;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tools redesign (2026-11-02): media asks whether a workspace hold keeps its data before it removes a 30-day-old recording
 * or conversion -- with the service token only, for a named workspace.
 */
class InternalRetentionHoldsRestApiTest {

    private final RetentionStore store = mock(RetentionStore.class);
    private final InternalRetentionHoldsRestApi api = new InternalRetentionHoldsRestApi(this.store, "secret-token");

    private static InternalRetentionHoldsRestApi.HoldsRequest of(Long tenantId) {
        InternalRetentionHoldsRestApi.HoldsRequest request = new InternalRetentionHoldsRestApi.HoldsRequest();
        request.setTenantId(tenantId);
        return request;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWorkspaceHoldIsSaidAndAPipelineHoldIsNot() {
        when(this.store.activeHolds(2960L)).thenReturn(new Holds(true, Collections.emptySet(), Collections.emptySet()));
        when(this.store.activeHolds(2961L)).thenReturn(new Holds(false, Collections.singleton(5L), Collections.emptySet()));

        ResponseEntity<?> held = this.api.holds("secret-token", of(2960L));
        ResponseEntity<?> notHeld = this.api.holds("secret-token", of(2961L));

        assertThat(((Map<String, Object>) ((ResponseDto) held.getBody()).getData())).containsEntry("workspace", true);
        assertThat(((Map<String, Object>) ((ResponseDto) notHeld.getBody()).getData())).containsEntry("workspace", false);
    }

    @Test
    void withoutTheTokenOrAWorkspaceNothingIsRead() {
        assertThat(this.api.holds(null, of(2960L)).getStatusCode().value()).isEqualTo(401);
        assertThat(this.api.holds("wrong", of(2960L)).getStatusCode().value()).isEqualTo(401);
        assertThat(this.api.holds("secret-token", of(null)).getStatusCode().value()).isEqualTo(400);
        assertThat(this.api.holds("secret-token", null).getStatusCode().value()).isEqualTo(400);
        verify(this.store, never()).activeHolds(anyLong());
    }
}
