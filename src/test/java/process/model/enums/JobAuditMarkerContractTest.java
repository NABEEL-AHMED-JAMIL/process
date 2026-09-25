package process.model.enums;

import org.junit.jupiter.api.Test;
import process.model.service.impl.QueryService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-77 (DEF-156): the 'Job started' marker is one value in two repositories -- process reads it, the Java
 * worker (service-1) writes it. Each side is pinned to the same literal and, where the other is checked out
 * beside it, compared with the other's source, so changing either alone fails a build. (The Python listener
 * this used to check, job-search's tpd_scrapping_listener.py, was deleted with job-search.)
 */
class JobAuditMarkerContractTest {

    /** The worker's side, as service-1 sends it. Changing it here means changing the worker in step. */
    private static final String WORKER_WRITES = "Job started";

    /** Where service-1 declares it, and how. */
    private static final String WORKER_SOURCE = "src/main/java/com/barco/service1/runtime/core/JobAuditMarker.java";
    private static final Pattern WORKER_DECLARES =
        Pattern.compile("String\\s+JOB_STARTED\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");

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
     * The worker's own source, when service-1 (the Java worker runtime, MIG-201) is checked out beside this project
     * -- it is on a developer's machine; CI has only this repository and skips, and the pin above holds. The
     * directory can be named with -Dworker.source.dir (a worktree, say). The worker sends its constant as the
     * Running callback's message, and service-1's own JobAuditMarkerContractTest proves it reaches the audit log.
     * A checkout from before MIG-77 has no such file and skips too.
     */
    @Test
    void theWorkerDeclaresTheSameMarker() throws Exception {
        Path source = workerSource();
        assumeTrue(source != null, "no service-1 checkout with " + WORKER_SOURCE + " beside process");
        Matcher declared = WORKER_DECLARES.matcher(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));

        assertThat(declared.find()).as("%s declares JOB_STARTED = \"...\"", source).isTrue();
        assertThat(declared.group(1)).as("the worker's JOB_STARTED in %s", source)
            .isEqualTo(JobAuditMarker.JOB_STARTED.logDetail());
    }

    private static Path workerSource() {
        List<Path> candidates = new ArrayList<>();
        String named = System.getProperty("worker.source.dir");
        if (named != null && !named.trim().isEmpty()) {
            candidates.add(Paths.get(named));
        }
        candidates.add(Paths.get("..", "service-1"));
        for (Path dir : candidates) {
            Path source = dir.resolve(WORKER_SOURCE);
            if (Files.isRegularFile(source)) {
                return source;
            }
        }
        return null;
    }
}
