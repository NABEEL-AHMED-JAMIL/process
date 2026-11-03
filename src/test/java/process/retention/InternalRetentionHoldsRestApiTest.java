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
    @SuppressWarnings("unchecked")
    void aRunIsHeldByItsRunHoldOrItsPipelinesHold() {
        when(this.store.activeHolds(2962L)).thenReturn(new Holds(false, Collections.singleton(5L), Collections.singleton(71L)));
        when(this.store.runPipeline(2962L, 72L)).thenReturn(5L);
        when(this.store.runPipeline(2962L, 73L)).thenReturn(6L);
        when(this.store.runPipeline(2962L, 74L)).thenReturn(null);
        for (long[] c : new long[][] {{71L, 1}, {72L, 1}, {73L, 0}, {74L, 0}}) {
            InternalRetentionHoldsRestApi.HoldsRequest request = of(2962L);
            request.setRunId(c[0]);
            Map<String, Object> answer = (Map<String, Object>) ((ResponseDto) this.api.holds("secret-token", request).getBody()).getData();
            assertThat(answer).as("run " + c[0]).containsEntry("workspace", false).containsEntry("run", c[1] == 1);
        }
        // Without a run named, the answer is the workspace's alone (as the tools' 30-day removal asks).
        assertThat(((Map<String, Object>) ((ResponseDto) this.api.holds("secret-token", of(2962L)).getBody()).getData())).doesNotContainKey("run");
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
