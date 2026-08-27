package process.socket;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
        payload.put("at", LocalDateTime.now().toString());
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
        payload.put("at", LocalDateTime.now().toString());
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
        payload.put("at", LocalDateTime.now().toString());
        this.send(tenantId, payload);
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
