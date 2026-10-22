package process.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.barco.platform.api.ApiTimes;
import org.barco.platform.event.PlatformEvent;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.forms.FormStore;
import process.outbox.OutboxWriter;
import process.pipeline.StepStore;
import process.pipeline.review.RunReviewEvents;
import process.pipeline.review.RunReviews;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Core's events out to the customer API's webhooks (MIG-333, ADR-025 decision 9). V203's api_event_out journal is written
 * with each change -- a run reaching Running, Completed or Failed, a step's made file, a settled form submission (triggers)
 * and a review decision ({@link CustomerEventJournal}). Every second this relay reads the rows not yet published, oldest
 * first, and turns each into the customer's events:
 *
 * <ul>
 *   <li>a run's status: run.started, run.completed or run.failed, data the Run as GET /v1/runs/{id} answers it -- as it
 *   stood at that moment (a run.started relayed after the run finished still says running); a completed run whose
 *   results wait for the customer's review is also run.review.requested, data {run, review};</li>
 *   <li>a decision: run.review.decided, data {run, review} with the review's status after it;</li>
 *   <li>a made file: file.available, data {runId, file} -- the file by its file id, never a bucket or a key;</li>
 *   <li>a settled form submission: form.submission.received, data the Submission (with the run it started, if any).</li>
 * </ul>
 * A run or job deleted since, or a file no longer there, sends nothing. Each event is a platform-commons PlatformEvent
 * (eventType the catalogue type, tenantId the workspace, occurredAt the journal row's time) whose payload is
 * {version, subject, data}, written to platform_outbox for {@value CustomerEventTypes#TOPIC}, keyed by the workspace, in
 * the transaction that stamps the row published: once, in order, per workspace.
 *
 * Row-level security (MIG-258): the scan of pending rows is across workspaces (listed in RowSecurityContractTest); each
 * row is built and stamped as its own workspace (RowSecurity.forTenant). One instance at a time (ShedLock), so the order
 * holds across replicas; off with the rest of the schedulers where process.scheduling.enabled is false.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class CustomerEventRelay {

    static final int BATCH = 200;
    static final String PRODUCER = "process";

    private static final Logger logger = LoggerFactory.getLogger(CustomerEventRelay.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OutboxWriter outbox;
    private final CustomerRuns runs;
    private final RunReviews reviews;
    private final StepStore steps;
    private final FormStore forms;

    @Autowired
    public CustomerEventRelay(JdbcTemplate jdbc, PlatformTransactionManager transactions, OutboxWriter outbox, CustomerRuns runs,
        RunReviews reviews, StepStore steps, FormStore forms) {
        this(jdbc, new TransactionTemplate(transactions), outbox, runs, reviews, steps, forms);
    }

    CustomerEventRelay(JdbcTemplate jdbc, TransactionTemplate transactions, OutboxWriter outbox, CustomerRuns runs, RunReviews reviews,
        StepStore steps, FormStore forms) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.outbox = outbox;
        this.runs = runs;
        this.reviews = reviews;
        this.steps = steps;
        this.forms = forms;
    }

    /** One journal row as the scan reads it. */
    static final class Pending {
        long outId;
        long tenantId;
        String kind;
        Long jobQueueId;
        Long jobId;
        Long runOutputId;
        Long submissionId;
        String jobStatus;
        String statusMessage;
        Instant runCreatedAt;
        Instant startedAt;
        Instant endedAt;
        Integer attempt;
        Instant occurredAt;
        /** MIG-355: kind 'alert' -- the alert, and whether it opened or resolved. */
        Long alertIncidentId;
        String alertEvent;
        /** MIG-420: kind 'item' -- the item that finished (run.item.completed). */
        Long runItemId;
    }

    /** One event to send: its type, what it is about, and its data in the customer API's shapes. */
    static final class Event {
        final String type;
        final String subject;
        final Map<String, Object> data;
        /** MIG-361: not the customer's -- the run's internal review, for the Task inbox (RunReviewEvents). */
        final Runnable internal;

        Event(String type, String subject, Map<String, Object> data) {
            this(type, subject, data, null);
        }

        private Event(String type, String subject, Map<String, Object> data, Runnable internal) {
            this.type = type;
            this.subject = subject;
            this.data = data;
            this.internal = internal;
        }

        static Event internal(String type, Runnable write) {
            return new Event(type, null, null, write);
        }
    }

    /** MIG-361: the run's internal review for the Task inbox; absent in hand-built tests. */
    private RunReviewEvents reviewEvents;

    @Autowired(required = false)
    public void setReviewEvents(RunReviewEvents reviewEvents) {
        this.reviewEvents = reviewEvents;
    }

    @Scheduled(initialDelayString = "${customer.events.relay.initial-delay-ms:20000}", fixedDelayString = "${customer.events.relay.poll-ms:1000}")
    @SchedulerLock(name = "relayCustomerEvents", lockAtMostFor = "5M")
    public void relayQuietly() {
        try {
            int sent = this.relay();
            if (sent > 0) {
                logger.debug("Relayed {} customer event(s).", sent);
            }
        } catch (RuntimeException failed) {
            logger.warn("The customer event relay failed; it tries again: {}", failed.getMessage());
        }
    }

    /** Publishes up to {@value #BATCH} journal rows, oldest first; answers how many events went out. */
    public int relay() {
        List<Pending> pending = RowSecurity.acrossTenants("the customer event relay (MIG-333) reads every workspace's"
            + " unpublished api_event_out rows (ids and kinds, in order); each is built, published and stamped as its own workspace",
            () -> this.jdbc.query("SELECT out_id, tenant_id, kind, job_queue_id, job_id, run_output_id, submission_id, job_status, "
            + "status_message, run_created_at, started_at, ended_at, attempt, occurred_at, alert_incident_id, alert_event, run_item_id "
            + "FROM api_event_out WHERE published_at IS NULL ORDER BY out_id LIMIT " + BATCH, CustomerEventRelay::pending));
        int sent = 0;
        for (Pending row : pending) {
            try {
                Integer count = RowSecurity.forTenant(row.tenantId, () -> this.transactions.execute(status -> this.publish(row)));
                sent += count == null ? 0 : count;
            } catch (RuntimeException failed) {
                // One row that cannot be built must not hold up its workspace for ever: it is stamped with no event and said so.
                logger.error("Customer event {} ({} of workspace {}) could not be built and is skipped: {}", row.outId, row.kind, row.tenantId,
                    failed.getMessage(), failed);
                RowSecurity.forTenant(row.tenantId, () -> this.jdbc.update("UPDATE api_event_out SET published_at = now(), event_count = 0 "
                    + "WHERE out_id = ? AND published_at IS NULL", row.outId));
            }
        }
        return sent;
    }

    /** The row's events into the outbox, and the row stamped; answers how many. Nothing when another relay stamped it first. */
    int publish(Pending row) {
        List<Event> all = this.eventsOf(row);
        List<Event> events = new ArrayList<>();
        List<Event> internal = new ArrayList<>();
        for (Event event : all) {
            (event.internal != null ? internal : events).add(event);
        }
        int stamped = this.jdbc.update("UPDATE api_event_out SET published_at = now(), event_count = ? WHERE out_id = ? AND published_at IS NULL",
            events.size(), row.outId);
        if (stamped == 0) {
            return 0;
        }
        for (Event event : internal) {
            event.internal.run();
        }
        for (Event event : events) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", CustomerEventTypes.PAYLOAD_VERSION);
            payload.put("subject", event.subject);
            payload.put("data", event.data);
            PlatformEvent<Object> envelope = PlatformEvent.at(row.occurredAt, event.type, row.tenantId, PRODUCER, payload);
            envelope.setEventId(UUID.randomUUID().toString());
            try {
                this.outbox.write(CustomerEventTypes.TOPIC, String.valueOf(row.tenantId), envelope.getEventId(), JSON.writeValueAsString(envelope));
            } catch (JsonProcessingException unwritable) {
                throw new IllegalStateException("A " + event.type + " event could not be written", unwritable);
            }
        }
        return events.size();
    }

    /** What a journal row announces, in order; empty when there is nothing left to announce. */
    List<Event> eventsOf(Pending row) {
        List<Event> events = new ArrayList<>();
        switch (row.kind) {
            case "run_status":
                this.runStatus(row, events);
                break;
            case "review_decided":
                this.reviewDecided(row, events);
                break;
            case "file_made":
                this.fileMade(row, events);
                break;
            case "submission":
                this.submission(row, events);
                break;
            case "alert":
                this.alert(row, events);
                break;
            case "item":
                this.itemCompleted(row, events);
                break;
            default:
                logger.warn("Customer event {} has a kind this Core does not know ({}); nothing is sent.", row.outId, row.kind);
        }
        return events;
    }

    private void runStatus(Pending row, List<Event> events) {
        String type = CustomerEventTypes.ofRunStatus(row.jobStatus);
        if (type == null || row.jobQueueId == null) {
            return;
        }
        Optional<CustomerRuns.Found> found = this.runs.found(row.tenantId, row.jobQueueId);
        if (!found.isPresent()) {
            return;
        }
        Map<String, Object> summary = this.reviews.summary(found.get().run, found.get().job);
        boolean completed = CustomerEventTypes.RUN_COMPLETED.equals(type);
        // The review as it stood when the run completed: waiting for every party it names, nothing decided -- no decision can
        // come before the completion. Read now, a decision made in the second before this row is relayed would otherwise
        // drop run.review.requested and say "approved" on run.completed (MIG-336: webhook_check's customer approves at once).
        Map<String, Object> review = completed ? reviewAtCompletion(summary) : null;
        String reviewWord = completed ? (String) review.get("status") : CustomerViews.reviewWordOf(summary.get("reviewStatus"));
        Map<String, Object> run = CustomerViews.run(asItStood(found.get().row, row), reviewWord);
        events.add(new Event(type, subjectOfRun(row.jobQueueId), run));
        if (completed && "pending".equals(reviewWord) && asksTheCustomer(summary)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run", run);
            data.put("review", review);
            events.add(new Event(CustomerEventTypes.REVIEW_REQUESTED, subjectOfRun(row.jobQueueId), data));
        }
        if (completed && "pending".equals(reviewWord) && asksUs(summary) && this.reviewEvents != null) {
            // MIG-361: the internal review waits -- a Task inbox task for the pipeline's reviewers.
            CustomerRuns.Found at = found.get();
            events.add(Event.internal(RunReviewEvents.REQUESTED, () -> this.reviewEvents.requested(row.tenantId, at.run, at.job,
                row.occurredAt)));
        }
    }

    private void reviewDecided(Pending row, List<Event> events) {
        if (row.jobQueueId == null) {
            return;
        }
        Optional<CustomerRuns.Found> found = this.runs.found(row.tenantId, row.jobQueueId);
        if (!found.isPresent()) {
            return;
        }
        Map<String, Object> summary = this.reviews.summary(found.get().run, found.get().job);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run", CustomerViews.run(found.get().row, CustomerViews.reviewWordOf(summary.get("reviewStatus"))));
        data.put("review", CustomerViews.review(summary));
        events.add(new Event(CustomerEventTypes.REVIEW_DECIDED, subjectOfRun(row.jobQueueId), data));
        if (this.reviewEvents != null && internalDone(summary)) {
            // MIG-361: a Task inbox task still open for the run's internal review is no longer needed.
            String status = String.valueOf(summary.get("reviewStatus"));
            events.add(Event.internal(RunReviewEvents.DECIDED, () -> this.reviewEvents.decided(row.tenantId, row.jobQueueId, status,
                row.occurredAt)));
        }
    }

    /** MIG-420: the run's items and their API view; absent in hand-built tests (an item event then sends nothing). */
    private process.pipeline.items.RunItemStore itemStore;
    private CustomerItems items;

    @Autowired(required = false)
    public void setItems(process.pipeline.items.RunItemStore itemStore, CustomerItems items) {
        this.itemStore = itemStore;
        this.items = items;
    }

    /**
     * MIG-420: run.item.completed -- an item of a run finished (done or failed) in an attempt; data {runId, pipelineId, attempt,
     * item}, the item as GET /v1/runs/{id}/items/{itemKey} answers it (its files by file id). An item replaced since by a retry
     * still says what that attempt did; a run deleted since sends nothing.
     */
    private void itemCompleted(Pending row, List<Event> events) {
        if (row.runItemId == null || this.itemStore == null || this.items == null) {
            return;
        }
        Optional<process.pipeline.items.ItemRow> item = this.itemStore.byId(row.runItemId);
        if (!item.isPresent() || !item.get().finished() || !this.runs.found(row.tenantId, item.get().jobQueueId).isPresent()) {
            return;
        }
        CustomerRuns.Found found = this.runs.found(row.tenantId, item.get().jobQueueId).get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", String.valueOf(item.get().jobQueueId));
        data.put("pipelineId", String.valueOf(found.row.jobId));
        data.put("attempt", item.get().attempt);
        data.put("item", this.items.eventItem(item.get(), item.get().jobQueueId));
        events.add(new Event(CustomerEventTypes.RUN_ITEM_COMPLETED, "run:" + item.get().jobQueueId + ":item:" + item.get().itemRef, data));
    }

    private void fileMade(Pending row, List<Event> events) {
        if (row.runOutputId == null) {
            return;
        }
        List<String> ids = this.jdbc.queryForList("SELECT file_id FROM run_output WHERE run_output_id = ?", String.class, row.runOutputId);
        if (ids.isEmpty() || ids.get(0) == null) {
            return;
        }
        String fileId = ids.get(0);
        Optional<StepStore.OutputRow> output = this.steps.outputByFileId(fileId);
        if (!output.isPresent() || !this.runs.found(row.tenantId, output.get().jobQueueId).isPresent()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", String.valueOf(output.get().jobQueueId));
        data.put("file", CustomerViews.madeFile(output.get(), fileId, Instant.now()));
        events.add(new Event(CustomerEventTypes.FILE_AVAILABLE, "file:" + fileId, data));
    }

    private void submission(Pending row, List<Event> events) {
        if (row.submissionId == null) {
            return;
        }
        Optional<FormStore.Submission> found = this.forms.submission(row.tenantId, row.submissionId);
        if (!found.isPresent()) {
            return;
        }
        FormStore.Submission submission = found.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(submission.submissionId));
        data.put("formId", String.valueOf(submission.formId));
        data.put("receivedAt", ApiTimes.utc(submission.submittedAt));
        data.put("runId", submission.jobQueueId == null ? null : String.valueOf(submission.jobQueueId));
        events.add(new Event(CustomerEventTypes.SUBMISSION_RECEIVED, "form-submission:" + submission.submissionId, data));
    }

    /**
     * The run as the journal row saw it: the run now when it still has that status (its end time and status line are
     * then complete), else the row's own copy -- a run.started relayed after the run finished says it was running.
     */
    static CustomerRunStore.Row asItStood(CustomerRunStore.Row now, Pending row) {
        if (row.jobStatus == null || row.jobStatus.equals(now.jobStatus)) {
            return now;
        }
        CustomerRunStore.Row then = new CustomerRunStore.Row();
        then.runId = now.runId;
        then.jobId = now.jobId;
        then.tenantId = now.tenantId;
        then.reference = now.reference;
        then.jobStatus = row.jobStatus;
        then.message = row.statusMessage;
        then.createdAt = row.runCreatedAt != null ? row.runCreatedAt : now.createdAt;
        then.startedAt = row.startedAt;
        then.endedAt = row.endedAt;
        then.attempt = row.attempt == null ? now.attempt : Math.max(1, row.attempt);
        // MIG-345: how that attempt came about, when it is the run's attempt now (a journal row of an earlier one says none).
        if (then.attempt == now.attempt) {
            then.retryMode = now.retryMode;
            then.retryFromStep = now.retryFromStep;
            then.retryPreviousAttempt = now.retryPreviousAttempt;
            then.retryReusedSteps = now.retryReusedSteps;
            then.retryVia = now.retryVia;
        }
        return then;
    }

    /**
     * MIG-361: the internal review needs nothing more -- the review is settled (approved, or rejected by either party), or
     * the workspace's own decision is in. A customer's approval alone leaves the internal task open.
     */
    @SuppressWarnings("unchecked")
    static boolean internalDone(Map<String, Object> summary) {
        Object status = summary.get("reviewStatus");
        if ("APPROVED".equals(status) || "REJECTED".equals(status)) {
            return true;
        }
        Object decisions = summary.get("decisions");
        return decisions instanceof List && ((List<Object>) decisions).stream()
            .anyMatch(d -> d instanceof Map && "internal".equals(((Map<String, Object>) d).get("party")));
    }

    /** MIG-361: the review needs the workspace's own (internal) decision. */
    @SuppressWarnings("unchecked")
    static boolean asksUs(Map<String, Object> summary) {
        Object required = summary.get("required");
        return required instanceof List && ((List<Object>) required).contains("internal");
    }

    @SuppressWarnings("unchecked")
    static boolean asksTheCustomer(Map<String, Object> summary) {
        Object required = summary.get("required");
        return required instanceof List && ((List<Object>) required).contains("customer");
    }

    /**
     * A completed run's review as it stood at the completion: pending for the parties the summary requires (not required
     * when it names none), with no decision, no decision time and no rerun yet.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> reviewAtCompletion(Map<String, Object> summary) {
        Map<String, Object> review = CustomerViews.review(summary);
        Object required = summary.get("required");
        boolean anyParty = required instanceof List && !((List<Object>) required).isEmpty();
        review.put("status", anyParty ? "pending" : "not_required");
        review.put("decidedAt", null);
        review.put("rerunRunId", null);
        review.put("decisions", new ArrayList<>());
        return review;
    }

    /**
     * MIG-355: alert.opened or alert.resolved -- the alert as it stands, its state the event's own (a quick open-and-resolve
     * still sends "open" first). Nothing when the alert is gone (its rule was removed before the relay got to it).
     */
    private void alert(Pending row, List<Event> events) {
        if (row.alertIncidentId == null) {
            return;
        }
        List<Map<String, Object>> found = this.jdbc.query("SELECT i.alert_incident_id, i.alert_rule_id, r.name, i.kind, i.severity, i.subject, "
            + "i.job_id, i.job_queue_id, i.title, i.detail, i.occurrences, i.first_seen_at, i.last_seen_at, i.resolved_at, i.resolved_how "
            + "FROM alert_incident i JOIN alert_rule r ON r.alert_rule_id = i.alert_rule_id WHERE i.alert_incident_id = ? AND i.tenant_id = ?",
            (rs, n) -> {
                boolean resolved = "resolved".equals(row.alertEvent);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("alertId", String.valueOf(rs.getLong("alert_incident_id")));
                data.put("ruleId", String.valueOf(rs.getLong("alert_rule_id")));
                data.put("ruleName", rs.getString("name"));
                data.put("kind", rs.getString("kind").toLowerCase(Locale.ROOT));
                data.put("severity", rs.getString("severity").toLowerCase(Locale.ROOT));
                data.put("state", resolved ? "resolved" : "open");
                data.put("subject", rs.getString("subject"));
                Object job = rs.getObject("job_id");
                Object run = rs.getObject("job_queue_id");
                data.put("pipelineId", job == null ? null : String.valueOf(job));
                data.put("runId", run == null ? null : String.valueOf(run));
                data.put("title", rs.getString("title"));
                data.put("detail", rs.getString("detail"));
                data.put("occurrences", rs.getInt("occurrences"));
                data.put("firstSeenAt", utc(rs.getObject("first_seen_at", OffsetDateTime.class)));
                data.put("lastSeenAt", utc(rs.getObject("last_seen_at", OffsetDateTime.class)));
                String how = rs.getString("resolved_how");
                data.put("resolvedAt", resolved ? utc(rs.getObject("resolved_at", OffsetDateTime.class)) : null);
                data.put("resolvedHow", resolved && how != null ? how.toLowerCase(Locale.ROOT) : null);
                return data;
            }, row.alertIncidentId, row.tenantId);
        if (!found.isEmpty()) {
            events.add(new Event("resolved".equals(row.alertEvent) ? CustomerEventTypes.ALERT_RESOLVED : CustomerEventTypes.ALERT_OPENED,
                "alert:" + row.alertIncidentId, found.get(0)));
        }
    }

    private static String utc(OffsetDateTime at) {
        return at == null ? null : at.toInstant().toString();
    }

    static String subjectOfRun(long jobQueueId) {
        return "run:" + jobQueueId;
    }

    private static Pending pending(ResultSet rs, int n) throws SQLException {
        Pending row = new Pending();
        row.outId = rs.getLong("out_id");
        row.tenantId = rs.getLong("tenant_id");
        row.kind = rs.getString("kind");
        row.jobQueueId = (Long) rs.getObject("job_queue_id");
        row.jobId = (Long) rs.getObject("job_id");
        row.runOutputId = (Long) rs.getObject("run_output_id");
        row.submissionId = (Long) rs.getObject("submission_id");
        row.jobStatus = rs.getString("job_status");
        row.statusMessage = rs.getString("status_message");
        row.runCreatedAt = instant(rs.getObject("run_created_at", OffsetDateTime.class));
        row.startedAt = instant(rs.getObject("started_at", OffsetDateTime.class));
        row.endedAt = instant(rs.getObject("ended_at", OffsetDateTime.class));
        row.attempt = (Integer) rs.getObject("attempt");
        Instant occurred = instant(rs.getObject("occurred_at", OffsetDateTime.class));
        row.occurredAt = occurred == null ? Instant.now() : occurred;
        row.alertIncidentId = (Long) rs.getObject("alert_incident_id");
        row.alertEvent = rs.getString("alert_event");
        row.runItemId = (Long) rs.getObject("run_item_id");
        return row;
    }

    private static Instant instant(OffsetDateTime at) {
        return at == null ? null : at.toInstant();
    }
}
