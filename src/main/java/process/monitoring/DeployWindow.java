package process.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A planned deploy of one service: etl-platform's scripts/deploy.sh opens it just before it recreates the container and
 * ends it once the new one is healthy (PlannedDeploys reads them). A restart, or a health check that is not UP, of that
 * service inside it is the deploy, not an incident: the platform rules do not alert on it, and Platform › Service health
 * marks it "planned deploy".
 *
 * <p>A window always ends: at {@link #end} when the deploy said so, and in any case at {@link #expires} (the deploy's
 * maximum, at most {@link #LONGEST} after its start), so a deploy that was killed before it could end its window does
 * not silence a service for longer than that. A restart after an expired window alerts.
 */
public final class DeployWindow {

    /** Before the start: the JVM's start time is read from its own clock and rounded; seconds, not minutes. */
    static final Duration LEAD = Duration.ofSeconds(30);
    /** After the end: the sample taken in the minute the deploy ended. */
    static final Duration GRACE = Duration.ofSeconds(60);
    /** However long a deploy asks for, its window is at most this. */
    static final Duration LONGEST = Duration.ofMinutes(60);

    public final String service;
    public final Instant start;
    public final Instant expires;
    /** When the deploy ended it; null while it runs (or when the deploy died before it could). */
    public final Instant end;
    /** How the deploy ended: "ok", "failed", or null while it runs. */
    public final String outcome;

    public DeployWindow(String service, Instant start, Instant expires, Instant end, String outcome) {
        this.service = service;
        this.start = start;
        Instant latest = start.plus(LONGEST);
        this.expires = expires == null || expires.isAfter(latest) ? latest : expires;
        this.end = end;
        this.outcome = outcome;
    }

    /** When the window closed, or will: its end, but never after it expires. */
    public Instant closes() {
        return this.end != null && this.end.isBefore(this.expires) ? this.end : this.expires;
    }

    /** Whether something of this service at {@code at} (a JVM start, a health sample) belongs to the deploy. */
    public boolean covers(Instant at) {
        return at != null && !at.isBefore(this.start.minus(LEAD)) && !at.isAfter(this.closes().plus(GRACE));
    }

    /** "open" (the deploy runs), "ended" (it said so) or "expired" (it never did; the window ran out). */
    public String state(Instant now) {
        if (this.end != null) {
            return "ended";
        }
        return now.isBefore(this.expires) ? "open" : "expired";
    }

    /** Whether any of these windows covers {@code at}. */
    public static boolean anyCovers(List<DeployWindow> windows, Instant at) {
        if (windows == null || at == null) {
            return false;
        }
        for (DeployWindow window : windows) {
            if (window.covers(at)) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> toMap(Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", this.service);
        out.put("start", this.start.toString());
        out.put("end", this.end == null ? null : this.end.toString());
        out.put("expires", this.expires.toString());
        out.put("closes", this.closes().toString());
        out.put("state", this.state(now));
        out.put("outcome", this.outcome);
        return out;
    }
}
