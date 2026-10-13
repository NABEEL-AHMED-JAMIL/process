package process.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.util.BusinessTime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MIG-347: a workspace's billing time zone, which run.date and a backfill's days are counted in (the architecture's time
 * rule: billing counts days in each workspace's zone, America/Chicago unless the platform sets another). Asked of billing
 * and kept {@link #FRESH} per workspace -- a zone changes at a month's start, at most -- so a run asks billing at most once
 * every ten minutes per workspace. When billing cannot answer, the last zone it gave is used; with none, the business
 * zone (America/Chicago), asked again after {@link #RETRY}. Never throws.
 */
@Component
public class WorkspaceZones {

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceZones.class);

    static final Duration FRESH = Duration.ofMinutes(10);
    static final Duration RETRY = Duration.ofMinutes(1);
    /** Bounded: a workspace a line, and no more workspaces than this are kept. */
    static final int MAX_KEPT = 10_000;

    private final BillingPort billing;
    private final Clock clock;
    private final Map<Long, Known> known = new ConcurrentHashMap<>();

    @Autowired
    public WorkspaceZones(BillingPort billing) {
        this(billing, Clock.systemUTC());
    }

    WorkspaceZones(BillingPort billing, Clock clock) {
        this.billing = billing;
        this.clock = clock;
    }

    private static final class Known {
        final ZoneId zone;
        final Instant until;
        final boolean fromBilling;

        Known(ZoneId zone, Instant until, boolean fromBilling) {
            this.zone = zone;
            this.until = until;
            this.fromBilling = fromBilling;
        }
    }

    /** The workspace's billing zone now. */
    public ZoneId zoneOf(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            return BusinessTime.ZONE;
        }
        Instant now = this.clock.instant();
        Known kept = this.known.get(tenantId);
        if (kept != null && now.isBefore(kept.until)) {
            return kept.zone;
        }
        try {
            ZoneId zone = ZoneId.of(this.billing.zoneOf(tenantId));
            this.keep(tenantId, new Known(zone, now.plus(FRESH), true));
            return zone;
        } catch (RuntimeException unanswered) {
            ZoneId fallback = kept != null && kept.fromBilling ? kept.zone : BusinessTime.ZONE;
            logger.warn("Workspace {}'s billing time zone could not be read ({}); using {} for a minute.", tenantId,
                unanswered.getMessage(), fallback.getId());
            this.keep(tenantId, new Known(fallback, now.plus(RETRY), kept != null && kept.fromBilling));
            return fallback;
        }
    }

    private void keep(long tenantId, Known value) {
        if (this.known.size() >= MAX_KEPT && !this.known.containsKey(tenantId)) {
            this.known.clear();
        }
        this.known.put(tenantId, value);
    }
}
