package process.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.Mockito;

/**
 * Owner #24 (2026-10-19), on MIG-167's rules: Platform › Engine settings changes the allow-listed dials (EngineDial) and
 * nothing else -- each as digits inside its range, the platform's value or (where the dial allows one) a workspace's own,
 * every change and reset audited, a reset back to the default. The watermarks are shown and never written (owner's D4);
 * a key that is not a dial (a secret, a URL) cannot be written at all.
 */
class EngineSettingsServiceTest {

    private final OrchestrationSettings settings = mock(OrchestrationSettings.class);
    private final EngineDials dials = mock(EngineDials.class);
    private final EngineSettingStore store = mock(EngineSettingStore.class);
    private final UserNameResolver names = mock(UserNameResolver.class);
    private EngineSettingsService service;

    @BeforeEach
    void setUp() {
        this.service = new EngineSettingsService(this.settings, this.dials, this.store, this.names);
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        when(this.names.namesFor(any())).thenReturn(Collections.emptyMap());
        when(this.settings.all()).thenReturn(Arrays.asList(
            new OrchestrationSettings.Setting("AUDIT_LOG_SYNC_LAST_RUN_TIME", "2026-09-20T23:18:17.307Z", "w", null, null),
            new OrchestrationSettings.Setting("QUEUE_FETCH_LIMIT", "5000", "d", null, null),
            new OrchestrationSettings.Setting("SCHEDULER_LAST_RUN_TIME", "2026-08-17T23:52:47.604023180", "w", null, null)));
        when(this.settings.find(anyString())).thenReturn(Optional.empty());
        when(this.store.overrideStamps()).thenReturn(Collections.emptyMap());
        when(this.dials.overrides()).thenReturn(Collections.emptyMap());
        for (EngineDial dial : EngineDial.values()) {
            when(this.dials.defaultOf(dial)).thenReturn(dial.builtIn());
            when(this.dials.platform(dial)).thenReturn(dial.builtIn());
            when(this.dials.storedPlatform(dial)).thenReturn(null);
        }
        when(this.dials.storedPlatform(EngineDial.QUEUE_FETCH_LIMIT)).thenReturn(5000L);
        when(this.dials.platform(EngineDial.QUEUE_FETCH_LIMIT)).thenReturn(5000L);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyDialIsListedWithItsRangeDefaultAndWhenItTakesEffectAndTheWatermarksReadOnly() {
        Map<String, Object> data = (Map<String, Object>) this.service.list().getData();
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("settings");
        List<EngineSettingDto> watermarks = (List<EngineSettingDto>) data.get("watermarks");

        assertThat(rows).extracting(r -> r.get("key")).containsExactly(Arrays.stream(EngineDial.values()).map(EngineDial::key).toArray());
        Map<String, Object> fetch = rows.get(0);
        assertThat(fetch).containsEntry("value", 5000L).containsEntry("defaultValue", 1000L).containsEntry("min", 1L)
            .containsEntry("max", 1_000_000L).containsEntry("atDefault", false).containsEntry("perWorkspace", false);
        assertThat((String) fetch.get("takesEffect")).isNotBlank();
        assertThat(rows).filteredOn(r -> r.get("key").equals("RUN_MEMORY_MB")).first()
            .satisfies(r -> assertThat(r).containsEntry("perWorkspace", true).containsEntry("atDefault", true));
        assertThat(watermarks).extracting(EngineSettingDto::getKey).containsExactly("SCHEDULER_LAST_RUN_TIME", "AUDIT_LOG_SYNC_LAST_RUN_TIME");
        assertThat(watermarks).extracting(EngineSettingDto::isEditable).containsOnly(false);
    }

    @Test
    void aPlatformValueInRangeIsStoredAndAudited() {
        ResponseDto saved = this.service.update("QUEUE_FETCH_LIMIT", "250", null);

        assertThat(saved.getStatus()).as(saved.getMessage()).isEqualTo("SUCCESS");
        assertThat(saved.getMessage()).contains("250").contains("next dispatch pass");
        verify(this.settings).setDial(EngineDial.QUEUE_FETCH_LIMIT, 250L, 1L);
        verify(this.store).record(EngineDial.QUEUE_FETCH_LIMIT, null, "SET", "5000", "250", 1L);
        verify(this.dials, Mockito.atLeastOnce()).invalidate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"5,000", " 5 000", "0", "-1", "abc", "1000001", "1e3", "", "12.5"})
    void anythingOutsideTheRangeOrNotDigitsIsRefusedAndNothingIsWritten(String value) {
        ResponseDto refused = this.service.update("QUEUE_FETCH_LIMIT", value, null);

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).contains("1 to 1,000,000");
        verify(this.settings, never()).setDial(any(), anyLong(), any());
        verifyNoInteractions(this.store);
    }

    @Test
    void eachDialHasItsOwnRange() {
        assertThat(this.service.update("RUN_MEMORY_MB", "8", null).getMessage()).contains("16 to 4,096 MB");
        assertThat(this.service.update("PIPELINE_MAX_STEPS", "501", null).getMessage()).contains("1 to 500 steps");
        assertThat(this.service.update("ENGINE_THREADS", "65", null).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.update("PIPELINE_MAX_STEPS", "500", null).getStatus()).isEqualTo("SUCCESS");
        verify(this.settings).setDial(EngineDial.PIPELINE_MAX_STEPS, 500L, 1L);
    }

    @Test
    void aWorkspaceGetsItsOwnValueOnlyWhereTheDialAllowsOne() {
        ResponseDto own = this.service.update("RUN_MEMORY_MB", "512", 2960L);
        assertThat(own.getStatus()).as(own.getMessage()).isEqualTo("SUCCESS");
        verify(this.store).setOverride(2960L, EngineDial.RUN_MEMORY_MB, 512L, 1L);
        verify(this.store).record(EngineDial.RUN_MEMORY_MB, 2960L, "SET", null, "512", 1L);
        verify(this.settings, never()).setDial(any(), anyLong(), any());

        ResponseDto shared = this.service.update("ENGINE_THREADS", "8", 2960L);
        assertThat(shared.getStatus()).isEqualTo("ERROR");
        assertThat(shared.getMessage()).contains("platform's alone");
        verify(this.store, never()).setOverride(eq(2960L), eq(EngineDial.ENGINE_THREADS), anyLong(), any());
    }

    @Test
    void aResetGoesBackToTheDefaultAndIsAudited() {
        when(this.dials.storedPlatform(EngineDial.RUN_MEMORY_MB)).thenReturn(256L);

        ResponseDto reset = this.service.reset("RUN_MEMORY_MB", null);

        assertThat(reset.getStatus()).as(reset.getMessage()).isEqualTo("SUCCESS");
        verify(this.settings).resetDial(EngineDial.RUN_MEMORY_MB, 1L);
        verify(this.store).record(EngineDial.RUN_MEMORY_MB, null, "RESET", "256", "128", 1L);
    }

    @Test
    void aWorkspaceResetGoesBackToThePlatformsValue() {
        when(this.store.override(2960L, EngineDial.BACKFILL_MAX_OPEN)).thenReturn(Optional.of("10"));

        assertThat(this.service.reset("BACKFILL_MAX_OPEN", 2960L).getStatus()).isEqualTo("SUCCESS");
        verify(this.store).removeOverride(2960L, EngineDial.BACKFILL_MAX_OPEN);
        verify(this.store).record(EngineDial.BACKFILL_MAX_OPEN, 2960L, "RESET", "10", "3", 1L);
    }

    @Test
    void resettingWhatIsAlreadyTheDefaultChangesNothing() {
        assertThat(this.service.reset("BATCH_ROWS", null).getMessage()).contains("already at its default");
        verify(this.settings, never()).resetDial(any(), any());
        verify(this.store, never()).record(any(), any(), anyString(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SCHEDULER_LAST_RUN_TIME", "AUDIT_LOG_SYNC_LAST_RUN_TIME"})
    void aWatermarkIsNeverWrittenOrResetFromTheScreen(String key) {
        ResponseDto refused = this.service.update(key, "2026-01-01T00:00:00Z", null);

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).contains("written only by");
        assertThat(this.service.reset(key, null).getStatus()).isEqualTo("ERROR");
        verify(this.settings, never()).writeWatermark(any(), anyString());
        verify(this.settings, never()).resetDial(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUE_FETCH_LIMIT_2", "jwt.secret.key", "INTERNAL_SERVICE_TOKEN", "process.pipeline.max-steps"})
    void aKeyThatIsNotADialCannotBeWrittenSecretsStayInTheEnvironment(String key) {
        ResponseDto refused = this.service.update(key, "10", null);

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).contains("No engine setting").contains("stay in the environment");
        assertThat(this.service.update(null, "10", null).getStatus()).isEqualTo("ERROR");
        verifyNoInteractions(this.store);
    }

    @Test
    void aTenantAdminCannotSeeChangeResetOrReadTheHistory() {
        TenantContext.set(2905L, "TENANT_ADMIN", 42L, "ops@medaxis.test");

        assertThat(this.service.list().getStatus()).isEqualTo("ERROR");
        assertThat(this.service.update("QUEUE_FETCH_LIMIT", "10", null).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.update("RUN_MEMORY_MB", "256", 2905L).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.reset("RUN_MEMORY_MB", 2905L).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.history(null, 10).getStatus()).isEqualTo("ERROR");
        verify(this.settings, never()).setDial(any(), anyLong(), any());
        verifyNoInteractions(this.store);
    }

    @ParameterizedTest
    @EnumSource(EngineDial.class)
    void everyDialsDefaultIsInsideItsRangeAndSaysWhenItTakesEffect(EngineDial dial) {
        assertThat(dial.builtIn()).isBetween(dial.min(), dial.max());
        assertThat(dial.takesEffect()).isNotBlank();
        assertThat(dial.clamp(dial.max() + 1)).isEqualTo(dial.max());
        assertThat(dial.clamp(dial.min() - 1)).isEqualTo(dial.min());
        assertThat(dial.problem(Long.toString(dial.builtIn()))).isEmpty();
    }

    @Test
    void theHistoryNamesWhoChangedWhat() {
        when(this.store.history(isNull(), eq(10))).thenReturn(Collections.emptyList());
        assertThat(this.service.history(null, 10).getStatus()).isEqualTo("SUCCESS");
        assertThat(this.service.history("NOT_A_DIAL", 10).getStatus()).isEqualTo("ERROR");
    }
}
