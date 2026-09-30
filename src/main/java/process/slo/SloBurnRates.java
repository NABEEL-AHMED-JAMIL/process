package process.slo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How fast each 99.99% SLO is spending its error budget right now (MIG-196; etl-platform docs/SLO.md section 3), from
 * stored rows: the multiwindow, multi-burn-rate rules of the Google SRE workbook, sized for the SLO's 28-day window --
 * the same four pairs observability/prometheus/slo-rules.yml holds, computed here without Prometheus.
 *
 * A burn rate is the window's error ratio over the budget's: (bad / counted) / (1 - target). 1 spends exactly the
 * budget in 28 days; 14.4 spends 2% of it in an hour. A rule holds when both its long and its short window burn
 * faster than its threshold (the short one says it is still happening) and the long window counted at least
 * {@value #MIN_COUNTED} operations (at 99.99%, one bad run in a quiet hour is a 100% error ratio).
 *
 * What counts is each SLI's own definition, unchanged: RunSloReport for pipeline runs (good Completed; bad Failed,
 * a decline, Interrupt of the stall sweep; skips, refusals, AI step failures and a person's closes excluded) and
 * Billing's MeterSloReport for billing events, asked through BillingSloClient. Billing's windows end five minutes
 * ago -- its grace: a younger unrolled event is pending, neither good nor bad.
 */
@Component
public class SloBurnRates {

    /** The SLO's window: every burn rate is relative to spending the budget over it. */
    public static final Duration SLO_WINDOW = Duration.ofDays(28);
    /** A long window with fewer counted operations never alerts (docs/SLO.md section 3). */
    public static final long MIN_COUNTED = 10;

    private static final Logger logger = LoggerFactory.getLogger(SloBurnRates.class);

    /** The four rules, fastest first. */
    public enum Rule {
        PAGE_1H(14.4, Duration.ofHours(1), Duration.ofMinutes(5), Severity.PAGE),
        PAGE_6H(6, Duration.ofHours(6), Duration.ofMinutes(30), Severity.PAGE),
        TICKET_1D(3, Duration.ofDays(1), Duration.ofHours(2), Severity.TICKET),
        TICKET_3D(1, Duration.ofDays(3), Duration.ofHours(6), Severity.TICKET);

        public final double threshold;
        public final Duration longWindow;
        public final Duration shortWindow;
        public final Severity severity;

        Rule(double threshold, Duration longWindow, Duration shortWindow, Severity severity) {
            this.threshold = threshold;
            this.longWindow = longWindow;
            this.shortWindow = shortWindow;
            this.severity = severity;
        }
    }

    public enum Severity { PAGE, TICKET }

    /** Counted operations in a window: the two numbers a burn rate needs. */
    public static final class Counts {
        public final long good;
        public final long bad;

        public Counts(long good, long bad) {
            this.good = good;
            this.bad = bad;
        }

        public long counted() {
            return this.good + this.bad;
        }
    }

    /** One SLI as the burn rates see it: its target and its good and bad counts for any window. */
    public interface Sli {
        /** A stable key: pipeline_execution, billing_events_priced. */
        String key();

        String title();

        double target();

        /** How far behind now its windows end. */
        Duration lag();

        /** Empty when this SLI is not measured here (not configured). */
        Optional<Counts> measure(Instant from, Instant to);
    }

    /** One window's figures. */
    public static final class Burn {
        public final Duration window;
        public final long good;
        public final long bad;
        /** Null when nothing was counted. */
        public final Double rate;

        Burn(Duration window, Counts counts, double target) {
            this.window = window;
            this.good = counts.good;
            this.bad = counts.bad;
            long counted = counts.counted();
            this.rate = counted == 0 ? null : ((double) counts.bad / counted) / (1 - target);
        }

        public long counted() {
            return this.good + this.bad;
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("window", human(this.window));
            out.put("good", this.good);
            out.put("bad", this.bad);
            out.put("burnRate", this.rate);
            return out;
        }
    }

    /** One SLI under one rule. */
    public static final class Evaluation {
        public final Sli sli;
        public final Rule rule;
        public final Instant asOf;
        /** Null when not measured. */
        public final Burn longBurn;
        public final Burn shortBurn;
        /** Why it was not measured; null when it was. */
        public final String unmeasured;

        Evaluation(Sli sli, Rule rule, Instant asOf, Burn longBurn, Burn shortBurn, String unmeasured) {
            this.sli = sli;
            this.rule = rule;
            this.asOf = asOf;
            this.longBurn = longBurn;
            this.shortBurn = shortBurn;
            this.unmeasured = unmeasured;
        }

        /** Both windows burn faster than the rule's threshold, over enough volume. */
        public boolean firing() {
            return this.longBurn != null && this.shortBurn != null
                && this.longBurn.counted() >= MIN_COUNTED
                && this.longBurn.rate != null && this.longBurn.rate > this.rule.threshold
                && this.shortBurn.rate != null && this.shortBurn.rate > this.rule.threshold;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sli", this.sli.key());
            out.put("rule", this.rule.name());
            out.put("severity", this.rule.severity.name().toLowerCase(Locale.ROOT));
            out.put("threshold", this.rule.threshold);
            out.put("asOf", this.asOf.toString());
            out.put("long", this.longBurn == null ? null : this.longBurn.toMap());
            out.put("short", this.shortBurn == null ? null : this.shortBurn.toMap());
            out.put("firing", this.firing());
            out.put("unmeasured", this.unmeasured);
            return out;
        }
    }

    private final List<Sli> slis;
    private final Clock clock;

    @Autowired
    public SloBurnRates(RunSloReport runs, BillingSloClient billing) {
        this(Arrays.asList(pipeline(runs), billing(billing)), Clock.systemUTC());
    }

    SloBurnRates(List<Sli> slis, Clock clock) {
        this.slis = Collections.unmodifiableList(new ArrayList<>(slis));
        this.clock = clock;
    }

    public List<Sli> slis() {
        return this.slis;
    }

    /** Every SLI under every rule, as the stored rows stand now. A failure to measure one SLI is reported on its rows. */
    public List<Evaluation> evaluate() {
        Instant now = this.clock.instant();
        List<Evaluation> out = new ArrayList<>();
        for (Sli sli : this.slis) {
            Instant to = now.minus(sli.lag());
            for (Rule rule : Rule.values()) {
                out.add(evaluate(sli, rule, to));
            }
        }
        return out;
    }

    private static Evaluation evaluate(Sli sli, Rule rule, Instant to) {
        try {
            Optional<Counts> longCounts = sli.measure(to.minus(rule.longWindow), to);
            Optional<Counts> shortCounts = sli.measure(to.minus(rule.shortWindow), to);
            if (!longCounts.isPresent() || !shortCounts.isPresent()) {
                return new Evaluation(sli, rule, to, null, null, "not configured here");
            }
            return new Evaluation(sli, rule, to, new Burn(rule.longWindow, longCounts.get(), sli.target()),
                new Burn(rule.shortWindow, shortCounts.get(), sli.target()), null);
        } catch (RuntimeException failed) {
            logger.warn("Could not measure {} for {}: {}", sli.key(), rule, failed.getMessage());
            return new Evaluation(sli, rule, to, null, null, "could not be measured");
        }
    }

    static Sli pipeline(RunSloReport runs) {
        return new Sli() {
            @Override public String key() { return "pipeline_execution"; }
            @Override public String title() { return "Pipeline execution"; }
            @Override public double target() { return RunSlo.TARGET; }
            @Override public Duration lag() { return Duration.ZERO; }
            @Override public Optional<Counts> measure(Instant from, Instant to) {
                RunSloReport.Window window = runs.measure(from, to);
                return Optional.of(new Counts(window.good, window.bad));
            }
        };
    }

    static Sli billing(BillingSloClient client) {
        return new Sli() {
            @Override public String key() { return "billing_events_priced"; }
            @Override public String title() { return "Billing"; }
            /** Billing's MeterSloReport.TARGET. */
            @Override public double target() { return 0.9999; }
            /** Billing's MeterSloReport.GRACE. */
            @Override public Duration lag() { return Duration.ofMinutes(5); }
            @Override public Optional<Counts> measure(Instant from, Instant to) {
                return client.measure(from, to);
            }
        };
    }

    static String human(Duration window) {
        if (window.toDays() > 0 && window.equals(Duration.ofDays(window.toDays()))) {
            return window.toDays() + "d";
        }
        if (window.toHours() > 0 && window.equals(Duration.ofHours(window.toHours()))) {
            return window.toHours() + "h";
        }
        return window.toMinutes() + "m";
    }
}
