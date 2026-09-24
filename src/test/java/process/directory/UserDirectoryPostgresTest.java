package process.directory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.identity.IdentityPort;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-153: user_directory, Core's projection of Identity's people. The newest state wins whatever order
 * events arrive in, a batch of ids is one query, and a person's display name is the one IdentityPort gives.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD.
 */
class UserDirectoryPostgresTest {

    private static ScratchPostgres db;
    private JdbcTemplate sql;
    private UserDirectory directory;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("mig153_directory");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        this.sql = db.jdbc();
        this.sql.update("TRUNCATE user_directory");
        this.directory = new UserDirectory(this.sql);
    }

    private static UserDirectory.Entry entry(long id, String fullName, String status, String at) {
        return new UserDirectory.Entry(id, 2901L, "u" + id + "@a.example", fullName, status, Instant.parse(at));
    }

    @Test
    void theTableIsFiveColumnsAndATimestampAndNothingElse() {
        assertThat(this.sql.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'user_directory'",
            String.class)).containsExactlyInAnyOrder("app_user_id", "tenant_id", "username", "full_name", "status", "updated_at");
        assertThat(this.sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f' "
            + "AND conrelid = 'user_directory'::regclass", Long.class)).as("no foreign key, to anything").isZero();
    }

    @Test
    void theNewestStateWinsWhateverOrderItArrivesIn() {
        assertThat(this.directory.apply(entry(7, "Ada", "Active", "2026-09-24T10:00:00Z"))).isTrue();
        assertThat(this.directory.apply(entry(7, "Ada Lovelace", "Active", "2026-09-24T11:00:00Z"))).isTrue();
        assertThat(this.directory.apply(entry(7, "Ada (old)", "Inactive", "2026-09-24T10:30:00Z")))
            .as("an older event, redelivered late, changes nothing").isFalse();

        UserDirectory.Entry held = this.directory.find(Collections.singletonList(7L)).get(7L);
        assertThat(held.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(held.getStatus()).isEqualTo("Active");
        assertThat(held.getUpdatedAt()).isEqualTo(Instant.parse("2026-09-24T11:00:00Z"));
    }

    @Test
    void aBatchIsAnsweredForTheIdsHeldAndOnlyThose() {
        this.directory.apply(entry(7, "Ada", "Active", "2026-09-24T10:00:00Z"));
        this.directory.apply(entry(8, null, "Delete", "2026-09-24T10:00:00Z"));

        Map<Long, UserDirectory.Entry> found = this.directory.find(Arrays.asList(7L, 8L, 9L));

        assertThat(found).containsOnlyKeys(7L, 8L);
        assertThat(found.get(7L).getDisplayName()).isEqualTo("Ada");
        assertThat(found.get(8L).getDisplayName()).as("no full name: the username").isEqualTo("u8@a.example");
        assertThat(this.directory.find(Collections.emptyList())).isEmpty();
    }

    @Test
    void writeBacksLandOnTheirOwnThreadAndNeverOverwriteANewerEvent() {
        UserDirectory direct = new UserDirectory(this.sql, Runnable::run);
        direct.apply(entry(7, "From the event", "Active", "2026-09-24T11:00:00Z"));
        direct.writeBack(Arrays.asList(entry(7, "Asked earlier", "Active", "2026-09-24T10:00:00Z"),
            entry(8, "Learned", "Active", "2026-09-24T10:00:00Z")));
        Map<Long, UserDirectory.Entry> held = direct.find(Arrays.asList(7L, 8L));
        assertThat(held.get(7L).getFullName()).isEqualTo("From the event");
        assertThat(held.get(8L).getFullName()).isEqualTo("Learned");
        direct.writeBack(Collections.singletonList(new UserDirectory.Entry(9L, null, "x", null, "Active", Instant.now())));
        assertThat(direct.find(Collections.singletonList(9L))).containsKey(9L);
    }

    @Test
    void anEntryFromThePortCarriesTheInstantTheQuestionWasAsked() {
        Instant asked = Instant.parse("2026-09-24T12:00:00Z");
        UserDirectory.Entry fromPort = UserDirectory.Entry.of(
            new IdentityPort.Person(9L, null, "root@platform", " Root ", "PLATFORM_ADMIN", "Active"), asked);
        assertThat(fromPort.getUpdatedAt()).isEqualTo(asked);
        assertThat(fromPort.getTenantId()).isNull();
        assertThat(fromPort.getDisplayName()).isEqualTo("Root");
    }
}
