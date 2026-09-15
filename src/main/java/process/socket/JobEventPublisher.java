package process.socket;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Pushes a job's state change to everyone watching that tenant's job list.
 *
 * The destination carries the tenant id -- /topic/jobs.{tenantId} -- so one publish reaches
 * every viewer of that tenant without a per-user fan-out, and StompAuthChannelInterceptor
 * refuses a SUBSCRIBE whose tenant does not match the subscriber's own token.
 *
 * The payload is deliberately small: enough to patch one row in place. A client that wants
 * the whole record still has fetchSourceJobDetailWithSourceJobId.
 *
 * @author Nabeel Ahmed
 */
@Component
public class JobEventPublisher {

    /*
     * Times on the wire are instants -- "2026-09-14T20:37:42.123Z" -- and not LocalDateTime,
     * which renders without any offset at all. A browser reads an offset-less timestamp as ITS
     * OWN local time, and this container runs UTC (TZ is unset in the image), so every pushed
     * time arrived in the page hours in the future. The jobs table advances its stall clock from
     * this field, and a start in the future made the elapsed time negative, which the stall check
     * reads as "not stalled" -- so the safety net for a worker that has gone quiet was switched
     * off for every job on any host that is not itself UTC.
     */

    public static final String TENANT_JOB_TOPIC = "/topic/jobs.";
    /** Platform admins see every tenant's jobs, so one tenant topic would never reach them. */
    public static final String ALL_TENANTS = "all";

    private final Logger logger = LoggerFactory.getLogger(JobEventPublisher.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final Gson gson = new Gson();

    public JobEventPublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publishStatus(Long tenantId, Long jobId, Long jobQueueId,
                              String runningStatus, String message) {
        if (tenantId == null || jobId == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "job.status");
        payload.put("jobId", jobId);
        payload.put("jobQueueId", jobQueueId);
        payload.put("jobRunningStatus", runningStatus);
        payload.put("message", message);
        payload.put("at", Instant.now().toString());
        this.send(tenantId, payload);
    }

    /** One log line as the pipeline reports it, so an open run-logs screen can append it. */
    public void publishLog(Long tenantId, Long jobId, Long jobQueueId, String message) {
        if (tenantId == null || jobQueueId == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "job.log");
        payload.put("jobId", jobId);
        payload.put("jobQueueId", jobQueueId);
        payload.put("message", message);
        payload.put("at", Instant.now().toString());
        this.send(tenantId, payload);
    }

    /** A job that has been created, edited, deleted or had its Active state flipped. */
    public void publishChanged(Long tenantId, Long jobId, String changeType) {
        if (tenantId == null || jobId == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", changeType);
        payload.put("jobId", jobId);
        payload.put("at", Instant.now().toString());
        this.send(tenantId, payload);
    }

    /**
     * publishStatus, held back until the surrounding transaction actually commits.
     *
     * BulkAction is @Transactional and the engine calls it inside longer units of work, so a
     * status published as the row is written is published before it is durable. If that
     * transaction then rolls back, every open jobs table has been told about a transition the
     * database does not have, and nothing arrives afterwards to correct it -- the screen only
     * comes right when somebody happens to reload.
     *
     * Outside a transaction it publishes at once, so the method is safe to call from anywhere.
     */
    public void publishStatusAfterCommit(final Long tenantId, final Long jobId, final Long jobQueueId,
                                         final String runningStatus, final String message) {
        if (tenantId == null || jobId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            this.publishStatus(tenantId, jobId, jobQueueId, runningStatus, message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishStatus(tenantId, jobId, jobQueueId, runningStatus, message);
            }
        });
    }

    /**
     * publishChanged, held back until the surrounding transaction actually commits.
     *
     * The client answers job.updated and job.toggled by re-reading that one row over HTTP,
     * because the event says a job changed without saying how. Announcing from inside the
     * writing transaction starts that read against a row the database has not committed yet,
     * so the reader is served the old values and the screen settles on stale data -- the exact
     * staleness the push exists to remove, now arriving faster and looking authoritative.
     *
     * Outside a transaction there is nothing to wait for, so it publishes at once; that keeps
     * the method safe to call from anywhere rather than only from a @Transactional service.
     */
    public void publishChangedAfterCommit(final Long tenantId, final Long jobId, final String changeType) {
        if (tenantId == null || jobId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            this.publishChanged(tenantId, jobId, changeType);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishChanged(tenantId, jobId, changeType);
            }
        });
    }

    private void send(Long tenantId, Map<String, Object> payload) {
        try {
            payload.put("tenantId", tenantId);
            String body = this.gson.toJson(payload);
            this.messagingTemplate.convertAndSend(TENANT_JOB_TOPIC + tenantId, body);
            // Same event on the platform-admin feed, which only a PLATFORM_ADMIN may join.
            this.messagingTemplate.convertAndSend(TENANT_JOB_TOPIC + ALL_TENANTS, body);
        } catch (Exception ex) {
            // A push nobody receives must never fail the operation that triggered it.
            this.logger.warn("Could not publish job event for tenant {}: {}", tenantId, ex.getMessage());
        }
    }

}
