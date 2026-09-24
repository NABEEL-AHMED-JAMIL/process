package process.directory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.identity.IdentityPort;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-153: the nightly anti-join of user_directory against Identity, and of the 48 demoted audit stamps
 * against both. A projection row that drifted is repaired; one Identity does not know is reported; an author
 * the projection lacks but Identity knows is filled in; an author nobody knows is reported with the columns
 * that name them. How old the oldest row is, is part of the report -- staleness measured, not assumed.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD.
 */
class UserDirectoryReconciliationPostgresTest {

    private static ScratchPostgres db;
    private JdbcTemplate sql;
    private UserDirectory directory;
    private final IdentityPort identity = mock(IdentityPort.class);

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("mig153_reconcile");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        this.sql = db.jdbc();
        this.sql.update("DELETE FROM scheduler");
        this.sql.update("DELETE FROM source_job");
        this.sql.update("TRUNCATE user_directory");
        this.directory = new UserDirectory(this.sql);
    }

    private static IdentityPort.Person person(long id, String fullName, String status) {
        return new IdentityPort.Person(id, 2901L, "u" + id + "@a.example", fullName, "TENANT_USER", status);
    }

    private void held(long id, String fullName, String at) {
        this.directory.apply(new UserDirectory.Entry(id, 2901L, "u" + id + "@a.example", fullName, "Active", Instant.parse(at)));
    }

    private void authoredBy(long jobId, long createdBy, Long updatedBy) {
        this.sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, "
            + "created_by, updated_by) VALUES (?, now(), 'Manual', 'j', 'Inactive', 1, 2901, ?, ?)", jobId, createdBy, updatedBy);
    }

    @Test
    void driftIsRepairedStrangersAreReportedAndAuthorsAreAccountedFor() {
        held(7, "Old Name", "2026-09-01T00:00:00Z");
        held(8, "Right", "2026-09-20T00:00:00Z");
        held(9, "Nobody", "2026-09-20T00:00:00Z");
        authoredBy(1, 10, 8L);
        authoredBy(2, 11, null);
        authoredBy(3, 11, 11L);
        Map<Long, IdentityPort.Person> known = new HashMap<>();
        known.put(7L, person(7, "New Name", "Active"));
        known.put(8L, person(8, "Right", "Active"));
        known.put(10L, person(10, "Ten", "Active"));
        when(this.identity.people(any())).thenAnswer(call -> {
            Map<Long, IdentityPort.Person> answer = new HashMap<>();
            for (Long id : call.<Collection<Long>>getArgument(0)) {
                if (known.containsKey(id)) answer.put(id, known.get(id));
            }
            return answer;
        });

        UserDirectoryReconciliation.Report report = new UserDirectoryReconciliation(this.sql, this.identity, this.directory).run();

        assertThat(report.isChecked()).isTrue();
        assertThat(report.getRepaired()).containsExactly(7L);
        assertThat(this.directory.find(Collections.singletonList(7L)).get(7L).getFullName()).isEqualTo("New Name");
        assertThat(report.getUnknownToIdentity()).containsExactly(9L);
        assertThat(report.getBackfilled()).containsExactly(10L);
        assertThat(this.directory.find(Collections.singletonList(10L))).containsKey(10L);
        assertThat(report.getDanglingAuthors()).containsOnlyKeys(11L);
        assertThat(report.getDanglingAuthors().get(11L)).containsEntry("source_job.created_by", 2L)
            .containsEntry("source_job.updated_by", 1L);
        assertThat(report.getOldestUpdatedAt()).as("measured before the repair").isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    void identityOutOfReachIsCouldNotCheckAndNothingIsTouched() {
        held(7, "Old Name", "2026-09-01T00:00:00Z");
        when(this.identity.people(any())).thenThrow(new IdentityPort.Unavailable("identity down", null));

        UserDirectoryReconciliation.Report report = new UserDirectoryReconciliation(this.sql, this.identity, this.directory).run();

        assertThat(report.isChecked()).isFalse();
        assertThat(report.getReason()).contains("identity down");
        assertThat(report.getRepaired()).isEmpty();
        assertThat(this.directory.find(Collections.singletonList(7L)).get(7L).getFullName()).isEqualTo("Old Name");
    }
}
