package process.customer;

/**
 * The customer API's events out (MIG-333, ADR-025 decision 9): the topic every service publishes them on and the types
 * Core sends. integration-service reads the topic and delivers each event to the workspace's webhook subscriptions; the
 * catalogue (etl-platform docs/api/event-types, served as GET /v1/event-types) says what each type's data is.
 *
 * <b>Generic.</b> The topic is the one shared way out: any service that writes a platform-commons PlatformEvent to it --
 * eventType a catalogue type, tenantId the workspace, payload {version, subject, data} with data in the customer API's
 * shapes (ids as strings, UTC times, never a bucket or a key) -- has its events subscribable, signed, retried and logged
 * with no webhook code of its own. A type not in the catalogue is not delivered.
 */
public final class CustomerEventTypes {

    /** Keyed by workspace id, so one workspace's events keep their order. */
    public static final String TOPIC = "platform.customer.events.v1";

    public static final String RUN_STARTED = "run.started";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String RUN_FAILED = "run.failed";
    public static final String REVIEW_REQUESTED = "run.review.requested";
    public static final String REVIEW_DECIDED = "run.review.decided";
    public static final String SUBMISSION_RECEIVED = "form.submission.received";
    public static final String FILE_AVAILABLE = "file.available";

    /** The payload's own version: what {version, subject, data} means. A type's data changes additively within it. */
    public static final int PAYLOAD_VERSION = 1;

    private CustomerEventTypes() {
    }

    /** The type a run's status is announced as, or null for a status a customer is not told about. */
    static String ofRunStatus(String jobStatus) {
        if (jobStatus == null) {
            return null;
        }
        switch (jobStatus) {
            case "Running":
                return RUN_STARTED;
            case "Completed":
                return RUN_COMPLETED;
            case "Failed":
                return RUN_FAILED;
            default:
                return null;
        }
    }
}
