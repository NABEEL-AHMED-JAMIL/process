package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.inbox.InboxTriggerRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_JOB;
import static process.tenancy.CoreProbeFixture.Caller;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.USER_OF_A;

/**
 * MIG-166's cross-tenant probe for the inbox trigger (MIG-239): a job's trigger and its arrivals, called by workspace A's
 * tenant user (at B's job and a colleague's) and administrator (at B's job). A probe passes when each is answered as a
 * job that does not exist, nothing of B's or the colleague's comes back or changes -- B's trigger and arrival rows
 * included -- and no run is started. A's own job still takes its trigger, so a probe that refused everything would not
 * pass. Against a real etl_job (CoreProbeFixture); opt-in like every ScratchPostgres test.
 */
class CoreCrossTenantProbeInboxPostgresTest {

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_inbox");
        JdbcTemplate sql = fx.db.jdbc();
        for (long job : new long[] {B_JOB, COLLEAGUE_JOB}) {
            sql.update("INSERT INTO job_inbox_trigger (job_id, file_pattern) VALUES (?, 'bravo-secret-*.csv')", job);
            sql.update("INSERT INTO inbox_arrival (arrival_id, job_id, bucket, storage_key, file_name, bytes, outcome, reason) VALUES "
                + "('99999999-9999-4999-8999-99999999999" + (job % 10) + "', ?, 'bravo-inbox', 'intake/2026/09/28/x-bravo-ledger.csv', "
                + "'bravo-ledger.csv', 10, 'Skipped', 'Bravo confidential reason')", job);
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void forgetEarlierCalls() throws Exception {
        fx.reset();
    }

    @Test
    void noInboxTriggerEndpointReadsOrChangesAnotherWorkspacesOrAColleaguesJob() throws Exception {
        String before = fx.foreignRows();

        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            long[] aimed = caller == USER_OF_A ? new long[] {B_JOB, COLLEAGUE_JOB} : new long[] {B_JOB};
            for (long theirs : aimed) {
                assertThat(fx.probe("GET sourceJob.json/inboxTrigger", caller, () -> fx.inboxTriggers.trigger(theirs)))
                    .contains(REFUSED).doesNotContain("bravo-secret");
                assertThat(fx.probe("POST sourceJob.json/inboxTrigger/save", caller,
                    () -> fx.inboxTriggers.save(new InboxTriggerRequest(theirs, true, "*")))).contains(REFUSED);
                assertThat(fx.probe("DELETE sourceJob.json/inboxTrigger", caller, () -> fx.inboxTriggers.delete(theirs)))
                    .contains(REFUSED);
                assertThat(fx.probe("GET sourceJob.json/inboxArrivals", caller, () -> fx.inboxTriggers.arrivals(theirs, 50)))
                    .contains(REFUSED).doesNotContain("bravo-ledger").doesNotContain("Bravo confidential");
            }
        }

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's, the platform's or the colleague's changed").isEqualTo(before);
        verify(fx.engine, never()).addInboxJobInQueue(any(), anyString(), anyString(), anyString(), anyString());
        verify(fx.engine, never()).addInboxJobInQueue(any(), anyString(), anyString(), anyList(), anyList());

        // A's own job still takes its trigger.
        assertThat(fx.probe("POST sourceJob.json/inboxTrigger/save(mine)", ADMIN_OF_A,
            () -> fx.inboxTriggers.save(new InboxTriggerRequest(A_JOB, true, "*.csv")))).contains("\"status\":\"SUCCESS\"");
        assertThat(fx.probe("GET sourceJob.json/inboxTrigger(mine)", ADMIN_OF_A, () -> fx.inboxTriggers.trigger(A_JOB)))
            .contains("*.csv");
    }
}
