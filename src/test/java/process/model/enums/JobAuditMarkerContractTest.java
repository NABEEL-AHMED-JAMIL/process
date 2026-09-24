package process.model.enums;

import org.junit.jupiter.api.Test;
import process.model.service.impl.QueryService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-77 (DEF-156): the 'Job started' marker is one value with two readers in two languages. Each side is
 * pinned to the same literal, so changing either alone fails a build.
 */
class JobAuditMarkerContractTest {

    /** The worker's side, as job-search writes it. Changing it here means changing the worker in step. */
    private static final String WORKER_WRITES = "Job started";

    @Test
    void theMarkerIsTheLiteralTheWorkerWrites() {
        assertThat(JobAuditMarker.JOB_STARTED.logDetail()).isEqualTo(WORKER_WRITES);
    }

    @Test
    void theRunReportMatchesTheMarkerExactlyAndNothingElse() {
        String sql = new QueryService().runReportRows("2026-09-01", "2026-09-21");

        assertThat(sql).contains("from job_audit_logs where log_detail = '" + JobAuditMarker.JOB_STARTED.logDetail() + "' ");
        // Exact equality is the rule: a LIKE would silently start matching a reworded line's neighbours.
        assertThat(sql.toLowerCase()).doesNotContain("log_detail like").doesNotContain("log_detail ilike");
    }

    /**
     * The worker's own source, when job-search is checked out beside this project (it is on a developer's
     * machine; CI has only this repository and skips). The Running callback must carry the marker verbatim.
     */
    @Test
    void theWorkerStillSendsTheMarkerOnPickUp() throws Exception {
        Path listener = Paths.get("..", "job-search", "etl", "tpd", "tpd_scrapping_listener.py");
        assumeTrue(Files.isRegularFile(listener), "job-search is not checked out beside process");
        String source = new String(Files.readAllBytes(listener), StandardCharsets.UTF_8);

        assertThat(source).containsPattern("JobStatus\\.RUNNING,\\s*\"" + WORKER_WRITES + "\"");
    }
}
