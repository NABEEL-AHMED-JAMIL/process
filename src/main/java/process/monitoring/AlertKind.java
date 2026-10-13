package process.monitoring;

import java.util.Locale;
import java.util.Optional;

/**
 * MIG-355: what an alert rule watches. The first four are a workspace's (its administrators make them, its people hear of
 * them); the other six are the platform's (tenant_id NULL, platform administrators). Each names its runbook entry
 * (etl-platform docs/runbooks/alerts.md#anchor) and says in one line what to do -- the line the bell, the mail and the
 * console show beside the alert.
 *
 * <ul>
 *   <li>{@link #RUN_FAILED}: threshold failed runs of a pipeline (or of any) within windowMinutes. Threshold 1 is "a run
 *   failed". Clears once fewer than threshold failures are in the window, or a later run of that pipeline completed.</li>
 *   <li>{@link #SCHEDULE_MISSED}: a schedule's slot with no run -- skipped (its run was still busy), missed (the platform
 *   was down), or overdue by more than five minutes -- within windowMinutes. Clears when no slot was missed in the window.</li>
 *   <li>{@link #RUN_SLOW}: a run going (or that ended within windowMinutes) longer than factor x its pipeline's p95 over
 *   the last 30 days (at least five completed runs to know "usual"). Clears when the run ends or leaves the window.</li>
 *   <li>{@link #REVIEW_WAITING}: a run's review, or an Approval step's request, still waiting after windowMinutes. Clears
 *   once decided.</li>
 *   <li>{@link #SERVICE_UNHEALTHY}: a service not UP (or not answering) for threshold minutes in a row.</li>
 *   <li>{@link #SERVICE_RESTARTED}: a service's JVM started again (its process start time moved) within windowMinutes.</li>
 *   <li>{@link #MEMORY_HIGH}: a service's heap after garbage collection at or over threshold percent (or its heap in use
 *   at 95% of its maximum). Clears five points under the threshold.</li>
 *   <li>{@link #KAFKA_LAG}: a consumer group threshold or more messages behind.</li>
 *   <li>{@link #DISK_LOW}: the platform's disk (as Core sees it: the Docker host's) under threshold percent free.</li>
 *   <li>{@link #BILLING_MISMATCH}: this month's billing reconciliation does not agree (billing-service's, hourly).</li>
 * </ul>
 */
public enum AlertKind {

    RUN_FAILED(false, "Runs failed", "run-failed",
        "Open Runs › Failed, read the failing step's error, fix its cause (the input, the pipeline's settings, or a service it calls), then retry from the failed step."),
    SCHEDULE_MISSED(false, "A schedule missed its slot", "schedule-missed",
        "Open the schedule's runs: a slot skipped because the previous run was still going means runs take longer than the schedule allows; a missed or overdue slot means the platform was down or the scheduler is stuck."),
    RUN_SLOW(false, "A run is slower than usual", "run-slow",
        "Open the run and its step timeline: the slow step is the one to look at (a bigger input, a slow API or database, or a busy service). Stop it if it is stuck."),
    REVIEW_WAITING(false, "A review is waiting too long", "review-waiting",
        "Remind the reviewers, or decide it yourself in the Review inbox; a run waiting for review holds its results back."),
    SERVICE_UNHEALTHY(true, "A service is unhealthy", "service-unhealthy",
        "Check the service's container (health, restarts, logs) and what it depends on (Postgres, Kafka, Redis); redeploy or restart it with etl-platform's scripts."),
    SERVICE_RESTARTED(true, "A service restarted", "service-restarted",
        "Read the service's logs from before the restart: an out-of-memory kill, a failed health check, or a deploy. Restarts that repeat need a fix, not a restart."),
    MEMORY_HIGH(true, "Memory high-water mark", "memory-high",
        "Find what holds memory (a big run, an export, a conversion); if it stays high, give the service a larger heap or find the leak before it is killed."),
    KAFKA_LAG(true, "Kafka consumers falling behind", "kafka-lag",
        "Check the consumer's service is up and not failing on a message (its .DLT topic, its logs); lag that only grows means it is stuck."),
    DISK_LOW(true, "Disk space low", "disk-low",
        "Free space before Postgres or Kafka stop: prune Docker build cache and dangling images (never volumes), and old run datasets."),
    BILLING_MISMATCH(true, "Billing reconciliation does not agree", "billing-mismatch",
        "Run etl-platform's scripts/billing-reconcile.sh for the month to see which workspace and meter differ; redraft a draft invoice, never an issued one.");

    private final boolean platform;
    private final String label;
    private final String anchor;
    private final String whatToDo;

    AlertKind(boolean platform, String label, String anchor, String whatToDo) {
        this.platform = platform;
        this.label = label;
        this.anchor = anchor;
        this.whatToDo = whatToDo;
    }

    /** The platform's (tenant_id NULL, platform administrators), not a workspace's. */
    public boolean isPlatform() {
        return this.platform;
    }

    public String label() {
        return this.label;
    }

    /** The runbook entry: etl-platform docs/runbooks/alerts.md#anchor. */
    public String runbook() {
        return "docs/runbooks/alerts.md#" + this.anchor;
    }

    public String whatToDo() {
        return this.whatToDo;
    }

    /** The webhook's word for it (alert.opened's data.kind). */
    public String wire() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public static Optional<AlertKind> of(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        for (AlertKind kind : values()) {
            if (kind.name().equals(upper)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
