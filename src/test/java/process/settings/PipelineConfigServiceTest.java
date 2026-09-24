package process.settings;

import java.util.HashMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;
import process.model.pojo.PipelineConfig;
import process.model.repository.PipelineConfigRepository;
import process.model.repository.SourceTaskRepository;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-167: the configuration store's console side. A secret is sealed on the way in and never comes back out --
 * not from list, not from add, not from update, not in any form the console could show -- and every entry belongs
 * to exactly one workspace.
 */
class PipelineConfigServiceTest {

    static final String CANARY = "hunter2-canary-7f3a";
    private static final long MINE = 2905L;
    private static final long THEIRS = 2901L;

    private final PipelineConfigRepository entries = mock(PipelineConfigRepository.class);
    private final SourceTaskRepository tasks = mock(SourceTaskRepository.class);
    private final IdentityPort identity = mock(IdentityPort.class);
    private final UserNameResolver names = mock(UserNameResolver.class);
    private final EncryptionUtil encryption = sealing();
    private PipelineConfigService service;

    static EncryptionUtil sealing() {
        EncryptionUtil util = new EncryptionUtil();
        ReflectionTestUtils.setField(util, "currentKeyId", "p2026a");
        ReflectionTestUtils.setField(util, "currentKey", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        return util;
    }

    @BeforeEach
    void setUp() {
        this.service = new PipelineConfigService(this.entries, this.tasks, this.encryption, this.identity, this.names);
        TenantContext.set(MINE, "TENANT_ADMIN", 42L, "ops@medaxis.test");
        when(this.entries.save(any(PipelineConfig.class))).thenAnswer(call -> {
            PipelineConfig saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(1000L);
            }
            if (saved.getCreatedAt() == null) {
                saved.setCreatedAt(new Timestamp(System.currentTimeMillis()));
            }
            return saved;
        });
        when(this.names.namesFor(any())).thenReturn(Collections.emptyMap());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aSecretIsSealedUnderTheCurrentKeyAndNeverReturned() throws Exception {
        ResponseDto added = this.service.add(request(null, "DB_PASSWORD", "SECRET", CANARY, "warehouse login"));

        assertThat(added.getStatus()).as(added.getMessage()).isEqualTo("SUCCESS");
        PipelineConfig stored = this.saved();
        assertThat(stored.getTenantId()).isEqualTo(MINE);
        assertThat(stored.getValue()).isNull();
        assertThat(stored.getValueSealed()).startsWith("kp2026a:").doesNotContain(CANARY);
        assertThat(this.encryption.decrypt(stored.getValueSealed())).isEqualTo(CANARY);
        assertThat(json(added)).doesNotContain(CANARY).doesNotContain(stored.getValueSealed()).contains("\"secretSet\":true");

        when(this.entries.findByTenantIdOrderByConfigKeyAsc(MINE)).thenReturn(Collections.singletonList(stored));
        String listed = json(this.service.list(null));
        assertThat(listed).contains("DB_PASSWORD").doesNotContain(CANARY).doesNotContain(stored.getValueSealed())
            .doesNotContain("\"value\"");
    }

    @Test
    void replacingASecretResealsItAndStillReturnsNothing() throws Exception {
        PipelineConfig existing = secret(1001L, MINE, "DB_PASSWORD", "old-secret");
        when(this.entries.findById(1001L)).thenReturn(Optional.of(existing));

        ResponseDto replaced = this.service.update(request(1001L, null, null, CANARY, null));

        assertThat(replaced.getStatus()).as(replaced.getMessage()).isEqualTo("SUCCESS");
        assertThat(this.encryption.decrypt(this.saved().getValueSealed())).isEqualTo(CANARY);
        assertThat(json(replaced)).doesNotContain(CANARY).doesNotContain("old-secret").doesNotContain("kp2026a:");
    }

    /** Editing only the description keeps the secret: a blank value is "unchanged", never "the secret is now blank". */
    @Test
    void anEditWithoutAValueKeepsTheSecret() throws Exception {
        PipelineConfig existing = secret(1001L, MINE, "DB_PASSWORD", "kept");
        String sealedBefore = existing.getValueSealed();
        when(this.entries.findById(1001L)).thenReturn(Optional.of(existing));

        this.service.update(request(1001L, null, null, "  ", "new words"));

        assertThat(this.saved().getValueSealed()).isEqualTo(sealedBefore);
        assertThat(this.saved().getDescription()).isEqualTo("new words");
        assertThat(this.saved().getValueSetBy()).as("a description edit does not re-date the secret").isNull();
    }

    /** Every console path, secrets and values alike: what was typed never reaches a log line, at any level. */
    @Test
    void noValueIsEverLogged() throws Exception {
        try (LogCapture logs = new LogCapture()) {
            this.service.add(request(null, "DB_PASSWORD", "SECRET", CANARY, null));
            PipelineConfig stored = this.saved();
            when(this.entries.findById(1000L)).thenReturn(Optional.of(stored));
            this.service.update(request(1000L, null, null, CANARY + "-2", null));
            when(this.entries.findByTenantIdOrderByConfigKeyAsc(MINE)).thenReturn(Collections.singletonList(stored));
            this.service.list(null);
            this.service.add(request(null, "INPUT_BUCKET", "VALUE", "value-canary-91c2", null));
            this.service.delete(1000L);

            assertThat(logs.everything()).contains("DB_PASSWORD").doesNotContain(CANARY).doesNotContain("value-canary-91c2")
                .doesNotContain("kp2026a:");
        }
    }

    @Test
    void theMaskIsNeverTakenForANewSecret() throws Exception {
        PipelineConfig existing = secret(1001L, MINE, "DB_PASSWORD", "kept");
        String sealedBefore = existing.getValueSealed();
        when(this.entries.findById(1001L)).thenReturn(Optional.of(existing));

        assertThat(this.service.update(request(1001L, null, null, "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022", null)).getStatus())
            .isEqualTo("ERROR");
        assertThat(this.service.add(request(null, "API_TOKEN", "SECRET", "********", null)).getStatus()).isEqualTo("ERROR");
        assertThat(existing.getValueSealed()).isEqualTo(sealedBefore);
        verify(this.entries, never()).save(any());
    }

    @Test
    void aValueIsStoredAndShownAsItIs() throws Exception {
        ResponseDto added = this.service.add(request(null, "INPUT_BUCKET", "VALUE", "etl-inputs", null));

        assertThat(added.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.saved().getValue()).isEqualTo("etl-inputs");
        assertThat(this.saved().getValueSealed()).isNull();
        assertThat(json(added)).contains("\"value\":\"etl-inputs\"");
    }

    @Test
    void aKeyIsUpperSnakeAndUniqueInItsWorkspace() throws Exception {
        assertThat(this.service.add(request(null, "db_password", "VALUE", "x", null)).getMessage()).contains("UPPER_SNAKE");
        assertThat(this.service.add(request(null, "", "VALUE", "x", null)).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.add(request(null, "A", "BLOB", "x", null)).getMessage()).contains("VALUE or SECRET");
        assertThat(this.service.add(request(null, "A", "VALUE", " ", null)).getMessage()).contains("value");
        when(this.entries.findByTenantIdAndConfigKey(MINE, "INPUT_BUCKET")).thenReturn(Optional.of(value(1002L, MINE, "INPUT_BUCKET", "a")));
        assertThat(this.service.add(request(null, "INPUT_BUCKET", "VALUE", "b", null)).getMessage()).contains("already exists");
        verify(this.entries, never()).save(any());
    }

    @Test
    void aSecretIsRefusedWhenThereIsNoCurrentKeyToSealItWith() throws Exception {
        EncryptionUtil keyless = new EncryptionUtil();
        this.service = new PipelineConfigService(this.entries, this.tasks, keyless, this.identity, this.names);

        ResponseDto refused = this.service.add(request(null, "DB_PASSWORD", "SECRET", CANARY, null));

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).contains("PROCESS_ENCRYPTION_KEY").doesNotContain(CANARY);
        verify(this.entries, never()).save(any());
    }

    @Test
    void anotherWorkspacesEntryIsNotFoundWhateverIsAsked() throws Exception {
        PipelineConfig theirs = secret(1003L, THEIRS, "DB_PASSWORD", "theirs");
        when(this.entries.findById(1003L)).thenReturn(Optional.of(theirs));

        assertThat(this.service.update(request(1003L, null, null, CANARY, null)).getMessage()).isEqualTo("Configuration entry 1003 not found.");
        assertThat(this.service.delete(1003L).getMessage()).isEqualTo("Configuration entry 1003 not found.");
        assertThat(this.service.update(request(999L, null, null, CANARY, null)).getMessage()).isEqualTo("Configuration entry 999 not found.");
        verify(this.entries, never()).save(any());
        verify(this.entries, never()).delete(any());

        // A tenant admin's tenantId is ignored: the list is always their own workspace's.
        this.service.list(THEIRS);
        verify(this.entries).findByTenantIdOrderByConfigKeyAsc(MINE);
    }

    @Test
    void aPlatformAdminNamesTheWorkspaceHeActsFor() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        assertThat(this.service.add(request(null, "INPUT_BUCKET", "VALUE", "x", null)).getMessage()).contains("workspace");
        when(this.identity.workspace(THEIRS)).thenReturn(Optional.empty());
        PipelineConfigDto forUnknown = request(null, "INPUT_BUCKET", "VALUE", "x", null);
        forUnknown.setTenantId(THEIRS);
        assertThat(this.service.add(forUnknown).getMessage()).contains("not a workspace");
        verify(this.entries, never()).save(any());

        when(this.identity.workspace(MINE)).thenReturn(Optional.of(new IdentityPort.Workspace(MINE, "MedAxis", "MCN", "Active")));
        PipelineConfigDto forMine = request(null, "INPUT_BUCKET", "VALUE", "x", null);
        forMine.setTenantId(MINE);
        assertThat(this.service.add(forMine).getStatus()).isEqualTo("SUCCESS");
        assertThat(this.saved().getTenantId()).isEqualTo(MINE);
    }

    @Test
    void theKeyAndKindOfAnEntryCannotChange() throws Exception {
        when(this.entries.findById(1002L)).thenReturn(Optional.of(value(1002L, MINE, "INPUT_BUCKET", "a")));

        assertThat(this.service.update(request(1002L, "OUTPUT_BUCKET", null, "b", null)).getMessage()).contains("cannot be renamed");
        assertThat(this.service.update(request(1002L, "INPUT_BUCKET", "SECRET", "b", null)).getMessage()).contains("cannot change");
        verify(this.entries, never()).save(any());
    }

    @Test
    void anEntryATaskUsesCannotBeDeleted() throws Exception {
        when(this.entries.findById(1001L)).thenReturn(Optional.of(secret(1001L, MINE, "DB_PASSWORD", "x")));
        when(this.tasks.countLiveTasksWithPayloadContaining(MINE, "${secret:DB_PASSWORD}")).thenReturn(2L);

        ResponseDto refused = this.service.delete(1001L);

        assertThat(refused.getStatus()).isEqualTo("ERROR");
        assertThat(refused.getMessage()).contains("2 tasks still use DB_PASSWORD");
        verify(this.entries, never()).delete(any());

        when(this.tasks.countLiveTasksWithPayloadContaining(anyLong(), anyString())).thenReturn(0L);
        assertThat(this.service.delete(1001L).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void theListSaysWhoSetEachEntryAndWhen() throws Exception {
        PipelineConfig entry = secret(1001L, MINE, "DB_PASSWORD", "x");
        entry.setCreatedBy(42L);
        entry.setValueSetBy(43L);
        entry.setValueSetAt(Timestamp.valueOf("2026-09-24 12:00:00"));
        entry.setUpdatedBy(44L);
        entry.setUpdatedAt(Timestamp.valueOf("2026-09-25 09:00:00"));
        when(this.entries.findByTenantIdOrderByConfigKeyAsc(MINE)).thenReturn(Collections.singletonList(entry));
        when(this.names.namesFor(any())).thenReturn(new HashMap<Long, String>() {{ put(42L, "Emily"); put(43L, "Sarah"); }});
        when(this.tasks.countLiveTasksWithPayloadContaining(MINE, "${secret:DB_PASSWORD}")).thenReturn(1L);

        @SuppressWarnings("unchecked")
        List<PipelineConfigDto> rows = (List<PipelineConfigDto>) this.service.list(null).getData();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSetByName()).isEqualTo("Sarah");
        assertThat(rows.get(0).getCreatedByName()).isEqualTo("Emily");
        assertThat(rows.get(0).getSetAt()).as("when the secret was set, not when the row was last edited")
            .isEqualTo(entry.getValueSetAt().toInstant().toString());
        assertThat(rows.get(0).getUsedByTasks()).isEqualTo(1L);
        assertThat(rows.get(0).getValue()).isNull();
    }

    private PipelineConfig saved() {
        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(this.entries, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    static PipelineConfigDto request(Long id, String key, String kind, String value, String description) {
        PipelineConfigDto dto = new PipelineConfigDto();
        dto.setId(id);
        dto.setKey(key);
        dto.setKind(kind);
        dto.setValue(value);
        dto.setDescription(description);
        return dto;
    }

    PipelineConfig secret(Long id, Long tenant, String key, String plain) {
        PipelineConfig entry = new PipelineConfig();
        entry.setId(id);
        entry.setTenantId(tenant);
        entry.setConfigKey(key);
        entry.setKind("SECRET");
        entry.setValueSealed(this.encryption.encrypt(plain));
        entry.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        return entry;
    }

    static PipelineConfig value(Long id, Long tenant, String key, String value) {
        PipelineConfig entry = new PipelineConfig();
        entry.setId(id);
        entry.setTenantId(tenant);
        entry.setConfigKey(key);
        entry.setKind("VALUE");
        entry.setValue(value);
        entry.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        return entry;
    }

    static String json(Object value) throws Exception {
        return new ObjectMapper().writeValueAsString(value);
    }

    static List<String> list(String... values) {
        return Arrays.asList(values);
    }
}
