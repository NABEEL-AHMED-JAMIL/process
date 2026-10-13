package process.monitoring;

/**
 * MIG-355: what a rule found this pass, about one subject ("pipeline:2976", "run:8297", "service:media", ...). Its subject
 * is the de-duplication key: one open alert per rule and subject.
 */
public final class Finding {

    public final String subject;
    public final String title;
    public final String detail;
    public final String link;
    public final Long jobId;
    public final Long runId;
    /** Null: the rule's own severity. */
    public final String severity;

    public Finding(String subject, String title, String detail, String link, Long jobId, Long runId, String severity) {
        this.subject = subject;
        this.title = title;
        this.detail = detail;
        this.link = link;
        this.jobId = jobId;
        this.runId = runId;
        this.severity = severity;
    }

    public static Finding of(String subject, String title, String detail, String link) {
        return new Finding(subject, title, detail, link, null, null, null);
    }

    @Override
    public String toString() {
        return this.subject + ": " + this.title;
    }
}
