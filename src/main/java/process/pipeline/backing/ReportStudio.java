package process.pipeline.backing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * Report Studio phase 5 (plan 1.11): what generate_report asks of media-service, with the service token and the run's
 * workspace (media's /internal/media/reportStudio). A report is made from a published version of a template or a step's
 * own design, exactly as Generate makes it in the console; the step streams its rows and follows the job.
 */
public interface ReportStudio {

    /** MIG-418: media-service refused because it is busy (429, 503); retryAfterSeconds is its Retry-After, or 0. */
    final class Busy extends IOException {
        public final int retryAfterSeconds;

        public Busy(String message, int retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }


    /** Why the step cannot run here (media's internal render is off); empty when it can. */
    Optional<String> unavailable();

    /**
     * Queues the report: {@code lines} is NDJSON, a head line {"head": {...}} then one JSON object a row. The same
     * idempotency key (the run's step and attempt) answers the same job.
     */
    JsonNode generate(long tenantId, Long userId, String userName, String idempotencyKey, Path lines) throws IOException;

    /** The job: status, errorCode, error, retryable, reportId, resultVersion, reportLabel, artifacts, checks. */
    JsonNode job(long tenantId, String jobId) throws IOException;

    /** A version's stored file (PDF or HTML), written to {@code out}. */
    void file(long tenantId, String reportId, int version, String format, OutputStream out) throws IOException;

    /**
     * The save-time (and run-time) check of designs and templates by id: found, name, ownerKind, currentPublishedVersion,
     * version, and -- given columns -- the row fields the version reads that they lack (missingFields).
     */
    JsonNode check(long tenantId, Collection<String> ids, String version, Collection<String> columns) throws IOException;

    /**
     * ZT-I24: the reports a run made, as media keeps them: reportId, reportLabel ("RPT-0013"), version, pages, fileName and
     * the PDF's sha256. An empty array where media is not asked.
     */
    default JsonNode runReports(long tenantId, long jobQueueId) throws IOException {
        return JsonNodeFactory.instance.arrayNode();
    }
}
