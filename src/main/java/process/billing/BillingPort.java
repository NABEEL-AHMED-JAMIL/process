package process.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * MIG-347: what Core asks billing-service -- a workspace's billing time zone (H9: the days and months billing counts in,
 * America/Chicago unless the platform set another) and what given runs cost (UX review B9's per-run shares of the priced
 * lines, the numbers Cost by pipeline and the run page show). Both over billing's internal API with the internal token.
 */
public interface BillingPort {

    /** The zone of the billing month under way in the workspace; throws when billing cannot answer. */
    String zoneOf(long tenantId);

    /** What each of these runs cost over the billing days [from, to]: the runs billing priced, by id. */
    RunCosts runCosts(long tenantId, LocalDate from, LocalDate to, Collection<Long> jobQueueIds);

    /** Runs' costs as billing answered them. */
    final class RunCosts {
        public final String currency;
        public final String zone;
        /** The runs billing has usage for, by run id; a run with none is not here. */
        public final Map<Long, BigDecimal> costs;

        public RunCosts(String currency, String zone, Map<Long, BigDecimal> costs) {
            this.currency = currency;
            this.zone = zone;
            this.costs = costs == null ? Collections.<Long, BigDecimal>emptyMap() : Collections.unmodifiableMap(costs);
        }
    }

    /** Billing could not be asked, or refused. */
    class BillingUnavailable extends RuntimeException {
        public BillingUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
