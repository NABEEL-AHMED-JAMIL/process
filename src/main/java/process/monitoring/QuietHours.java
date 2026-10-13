package process.monitoring;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * MIG-355: a rule's quiet hours -- a wall-clock span in a time zone, which may cross midnight (22:00 to 07:00). Inside
 * it, mail is held (and sent when the span ends, if the alert is still open); the bell and webhooks are not held: the
 * bell is read when someone looks, and a webhook feeds a machine.
 */
public final class QuietHours {

    private QuietHours() {
    }

    /** Whether {@code at} falls in [start, end) on the zone's wall clock; never quiet without both ends. */
    public static boolean quiet(LocalTime start, LocalTime end, String zone, Instant at) {
        if (start == null || end == null || start.equals(end)) {
            return false;
        }
        ZoneId id;
        try {
            id = ZoneId.of(zone == null || zone.trim().isEmpty() ? AlertRules.DEFAULT_ZONE : zone.trim());
        } catch (DateTimeException unknown) {
            id = ZoneId.of(AlertRules.DEFAULT_ZONE);
        }
        LocalTime now = at.atZone(id).toLocalTime();
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        // Across midnight: quiet from start to midnight, and from midnight to end.
        return !now.isBefore(start) || now.isBefore(end);
    }

    public static boolean quiet(AlertRule rule, Instant at) {
        return quiet(rule.quietStart, rule.quietEnd, rule.quietZone, at);
    }
}
