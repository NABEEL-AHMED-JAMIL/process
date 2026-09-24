package process.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-167 (MIG-136's rules carried across): the engine settings screen edits QUEUE_FETCH_LIMIT, as a whole number
 * the dispatcher can use, and nothing else. The two watermarks are shown and never written here (owner's D4). There
 * is no way to delete or rename a setting: the engine reads them by name.
 */
class EngineSettingsServiceTest {

    private final OrchestrationSettings settings = mock(OrchestrationSettings.class);
    private final UserNameResolver names = mock(UserNameResolver.class);
    private EngineSettingsService service;

    @BeforeEach
    void setUp() {
        this.service = new EngineSettingsService(this.settings, this.names);
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.names.namesFor(any())).thenReturn(Collections.emptyMap());
        when(this.settings.all()).thenReturn(Arrays.asList(
            new OrchestrationSettings.Setting("AUDIT_LOG_SYNC_LAST_RUN_TIME", "2026-09-20T23:18:17.307Z", "w", null, null),
            new OrchestrationSettings.Setting("QUEUE_FETCH_LIMIT", "5000", "d", null, null),
            new OrchestrationSettings.Setting("SCHEDULER_LAST_RUN_TIME", "2026-08-17T23:52:47.604023180", "w", null, null)));
        when(this.settings.find("QUEUE_FETCH_LIMIT")).thenReturn(Optional.of(
            new OrchestrationSettings.Setting("QUEUE_FETCH_LIMIT", "250", "d", null, 1L)));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyTheFetchLimitIsEditable() {
        List<EngineSettingDto> rows = (List<EngineSettingDto>) this.service.list().getData();

        assertThat(rows).extracting(EngineSettingDto::getKey)
            .containsExactly("QUEUE_FETCH_LIMIT", "SCHEDULER_LAST_RUN_TIME", "AUDIT_LOG_SYNC_LAST_RUN_TIME");
        assertThat(rows).extracting(EngineSettingDto::isEditable).containsExactly(true, false, false);
        assertThat(rows.get(0).getValue()).isEqualTo("5000");
    }

    @Test
    void aWholeNumberInRangeIsStored() {
        ResponseDto saved = this.service.update("QUEUE_FETCH_LIMIT", "250");

        assertThat(saved.getStatus()).as(saved.getMessage()).isEqualTo("SUCCESS");
        verify(this.settings).setQueueFetchLimit(250L, 1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5,000", " 5000", "5000 ", "0", "-1", "abc", "1000001", "1e3", "", "12.5"})
    void anythingTheDispatcherCouldNotUseIsRefused(String value) {
        ResponseDto refused = this.service.update("QUEUE_FETCH_LIMIT", value);

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).contains("whole number from 1 to 1000000");
        verify(this.settings, never()).setQueueFetchLimit(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCHEDULER_LAST_RUN_TIME", "AUDIT_LOG_SYNC_LAST_RUN_TIME"})
    void aWatermarkIsNeverWrittenFromTheScreen(String key) {
        ResponseDto refused = this.service.update(key, "2026-01-01T00:00:00Z");

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).contains("written only by");
        verify(this.settings, never()).setQueueFetchLimit(anyLong(), any());
        verify(this.settings, never()).writeWatermark(any(), anyString());
    }

    @Test
    void aSettingThatDoesNotExistCannotBeCreatedOrRenamedInto() {
        assertThat(this.service.update("QUEUE_FETCH_LIMIT_2", "10").getMessage()).contains("No engine setting");
        assertThat(this.service.update(null, "10").getStatus()).isEqualTo("ERROR");
    }

    @Test
    void aTenantAdminCannotSeeOrChangeThem() {
        TenantContext.set(2905L, "TENANT_ADMIN", 42L, "ops@medaxis.test");

        assertThat(this.service.list().getStatus()).isEqualTo("ERROR");
        assertThat(this.service.update("QUEUE_FETCH_LIMIT", "10").getStatus()).isEqualTo("ERROR");
        verify(this.settings, never()).setQueueFetchLimit(anyLong(), any());
    }
}
