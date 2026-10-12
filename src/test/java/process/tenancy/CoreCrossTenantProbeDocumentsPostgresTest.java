package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.documents.DocumentTriggerRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_JOB;
import static process.tenancy.CoreProbeFixture.Caller;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.USER_OF_A;
import static process.tenancy.CoreProbeFixture.REFUSED;

/**
 * MIG-166's cross-tenant probe for the document trigger (MIG-364): a job's trigger and its approvals, called by workspace
 * A's tenant user (at B's job and a colleague's) and administrator (at B's job). A probe passes when each is answered as
 * a job that does not exist, nothing of B's or the colleague's comes back or changes -- B's trigger and approval rows
 * included -- and no run is started. A's own job still takes its trigger, so a probe that refused everything would not
 * pass. Against a real etl_job (CoreProbeFixture); opt-in like every ScratchPostgres test.
 */
class CoreCrossTenantProbeDocumentsPostgresTest {

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_documents");
        JdbcTemplate sql = fx.db.jdbc();
        for (long job : new long[] {B_JOB, COLLEAGUE_JOB}) {
            sql.update("INSERT INTO job_document_trigger (job_id, document_type_id, batch_size) VALUES (?, 777001, 7)", job);
            sql.update("INSERT INTO document_approval (job_id, extraction_id, type_version, document_type_id, outcome, reason) VALUES "
                + "(?, ?, 1, 777001, 'Skipped', 'Bravo confidential reason')", job, 880000 + job % 10);
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

    private static DocumentTriggerRequest request(long jobId, long type) {
        DocumentTriggerRequest r = new DocumentTriggerRequest();
        r.setJobId(jobId);
        r.setDocumentTypeId(type);
        r.setEnabled(true);
        return r;
    }

    @Test
    void noDocumentTriggerEndpointReadsOrChangesAnotherWorkspacesOrAColleaguesJob() throws Exception {
        String before = fx.foreignRows();

        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            long[] aimed = caller == USER_OF_A ? new long[] {B_JOB, COLLEAGUE_JOB} : new long[] {B_JOB};
            for (long theirs : aimed) {
                assertThat(fx.probe("GET sourceJob.json/documentTrigger", caller, () -> fx.documentTriggers.trigger(theirs)))
                    .contains(REFUSED).doesNotContain("777001");
                assertThat(fx.probe("POST sourceJob.json/documentTrigger/save", caller,
                    () -> fx.documentTriggers.save(request(theirs, 1007)))).contains(REFUSED);
                assertThat(fx.probe("DELETE sourceJob.json/documentTrigger", caller, () -> fx.documentTriggers.delete(theirs)))
                    .contains(REFUSED);
                assertThat(fx.probe("GET sourceJob.json/documentApprovals", caller, () -> fx.documentTriggers.approvals(theirs, 50)))
                    .contains(REFUSED).doesNotContain("88000").doesNotContain("Bravo confidential");
            }
        }

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's, the platform's or the colleague's changed").isEqualTo(before);
        verify(fx.engine, never()).addDocumentJobInQueue(any(), anyLong(), anyList());

        // A's own job still takes its trigger.
        assertThat(fx.probe("POST sourceJob.json/documentTrigger/save(mine)", ADMIN_OF_A,
            () -> fx.documentTriggers.save(request(A_JOB, 1007)))).contains("\"status\":\"SUCCESS\"");
        assertThat(fx.probe("GET sourceJob.json/documentTrigger(mine)", ADMIN_OF_A, () -> fx.documentTriggers.trigger(A_JOB)))
            .contains("1007");
    }
}
