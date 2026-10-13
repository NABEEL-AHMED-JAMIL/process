package process.monitoring;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import process.identity.IdentityPort;

/**
 * MIG-355: one evaluation pass over every enabled rule (MonitoringCycle calls it once a minute, under ShedLock).
 *
 * <ol>
 *   <li>The rules: one statement. What they read: one statement per kind for every workspace that has a rule of it
 *   (WorkspaceSignals), and the health samples and probes for the platform's. The open alerts of those rules: one
 *   statement. No query per rule, pipeline or run.</li>
 *   <li>Each rule's findings (RuleChecks) against its open alerts: a new subject opens an alert, one still found is
 *   touched, one no longer found is resolved (AUTO). Each rule in its own transaction, as its own workspace
 *   (RowSecurity.forTenant) -- the platform's across workspaces -- so one workspace's rule never writes or tells another's,
 *   and one failing rule does not stop the rest.</li>
 *   <li>Telling (AlertDelivery), in the same transaction: an alert that opened, or one whose telling failed before
 *   (notified_at still empty -- Identity could not answer), at most {@value #MAX_TOLD_PER_RULE} a rule a pass (the rest
 *   next minute); mail held by quiet hours once they are over; a resolved alert's notice and webhook.</li>
 *   <li>Alerts of a rule turned off are resolved.</li>
 * </ol>
 * While the collector has not sampled for {@link #STALE} the platform's sample rules are not evaluated: no alert opens or
 * closes on readings that are not there. A service's restart or unhealthy minutes inside a planned deploy window
 * (PlannedDeploys: scripts/deploy.sh announces each deploy) open no alert; outside one they do.
 */
@Component
public class AlertEngine {

    static final int MAX_TOLD_PER_RULE = 20;
    static final Duration STALE = Duration.ofMinutes(3);

    private static final Logger logger = LoggerFactory.getLogger(AlertEngine.class);

    private final AlertRules rules;
    private final AlertIncidents incidents;
    private final WorkspaceSignals signals;
    private final HealthCollector health;
    private final AlertDelivery delivery;
    private final PlannedDeploys planned;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    /** Without planned deploys: every restart alerts. */
    public AlertEngine(AlertRules rules, AlertIncidents incidents, WorkspaceSignals signals, HealthCollector health, AlertDelivery delivery,
        JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(rules, incidents, signals, health, delivery, PlannedDeploys.none(), jdbc, transactions);
    }

    @Autowired
    public AlertEngine(AlertRules rules, AlertIncidents incidents, WorkspaceSignals signals, HealthCollector health, AlertDelivery delivery,
        PlannedDeploys planned, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.rules = rules;
        this.incidents = incidents;
        this.signals = signals;
        this.health = health;
        this.delivery = delivery;
        this.planned = planned;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What a pass did, for the log and the tests. */
    public static final class Pass {
        public int rules;
        public int opened;
        public int touched;
        public int resolved;
        public int told;
        public int heldMailSent;
        public int failedRules;
        public final List<Long> openedIds = new ArrayList<>();
    }

    /** What the pass read, once, for every rule. */
    static final class Reads {
        List<WorkspaceSignals.Failure> failures = Collections.emptyList();
        Map<Long, Instant> lastCompleted = Collections.emptyMap();
        List<WorkspaceSignals.Skip> skips = Collections.emptyList();
        List<WorkspaceSignals.Overdue> overdue = Collections.emptyList();
        List<WorkspaceSignals.Timed> slowCandidates = Collections.emptyList();
        Map<Long, WorkspaceSignals.Usual> usual = Collections.emptyMap();
        List<WorkspaceSignals.Waiting> waiting = Collections.emptyList();
        Map<String, List<RuleChecks.Sample>> recent;
        Map<String, RuleChecks.Probe> probes;
        Map<String, List<DeployWindow>> planned = Collections.emptyMap();
        boolean samplesFresh;
    }

    public Pass evaluate(Instant now) {
        Pass pass = new Pass();
        this.resolveTurnedOff(now, pass);
        List<AlertRule> all = this.rules.enabledEverywhere();
        pass.rules = all.size();
        if (all.isEmpty()) {
            return pass;
        }
        Reads reads = this.read(all, now);
        List<Long> ids = new ArrayList<>();
        all.forEach(rule -> ids.add(rule.id));
        Map<Long, Map<String, AlertIncidents.Incident>> open = RowSecurity.acrossTenants("the alert evaluator (MIG-355) reads the open alerts"
            + " of every enabled rule; each rule's are then written as its own workspace", () -> this.incidents.openOf(ids));
        AlertDelivery.Audience audience = new AlertDelivery.Audience();
        for (AlertRule rule : all) {
            if (rule.kind == null) {
                continue;
            }
            try {
                this.asOwner(rule, () -> this.transaction.execute(status -> {
                    this.apply(rule, reads, open.getOrDefault(rule.id, Collections.emptyMap()), audience, now, pass);
                    return null;
                }));
            } catch (RuntimeException failed) {
                pass.failedRules++;
                logger.warn("Alert rule {} ({}) could not be evaluated this pass; it is tried again next minute: {}", rule.id, rule.kind,
                    failed.getMessage());
            }
        }
        if (pass.opened + pass.resolved + pass.told > 0) {
            logger.info("Alert pass: {} rules, {} opened, {} still open, {} resolved, {} told, {} held mails sent{}.", pass.rules, pass.opened,
                pass.touched, pass.resolved, pass.told, pass.heldMailSent, pass.failedRules == 0 ? "" : ", " + pass.failedRules + " failed");
        }
        return pass;
    }

    /** One rule against its open alerts. */
    void apply(AlertRule rule, Reads reads, Map<String, AlertIncidents.Incident> open, AlertDelivery.Audience audience, Instant now, Pass pass) {
        Optional<List<Finding>> found = this.findings(rule, reads, open.keySet(), now);
        if (!found.isPresent()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        List<AlertIncidents.Incident> toTell = new ArrayList<>();
        for (Finding finding : found.get()) {
            if (!seen.add(finding.subject)) {
                continue;
            }
            AlertIncidents.Incident already = open.get(finding.subject);
            if (already != null) {
                this.incidents.touch(already.id, finding, now);
                pass.touched++;
                if (already.notified == null) {
                    toTell.add(already);
                } else if (already.emailHeld && rule.email && !QuietHours.quiet(rule, now)) {
                    try {
                        this.delivery.heldMail(rule, already, this.delivery.recipients(rule, audience), this.incidents, now);
                        pass.heldMailSent++;
                    } catch (IdentityPort.Unavailable unavailable) {
                        logger.warn("Alert {}'s held mail waits: Identity could not say who to send it to.", already.id);
                    }
                }
                continue;
            }
            Optional<Long> made = this.incidents.open(rule, finding, now);
            if (made.isPresent()) {
                pass.opened++;
                pass.openedIds.add(made.get());
                this.incidents.find(made.get()).ifPresent(toTell::add);
            }
        }
        int told = 0;
        for (AlertIncidents.Incident incident : toTell) {
            if (told >= MAX_TOLD_PER_RULE) {
                break;
            }
            List<IdentityPort.Person> to;
            try {
                to = this.delivery.recipients(rule, audience);
            } catch (IdentityPort.Unavailable unavailable) {
                // The alert stays open and untold (notified_at empty): the next pass tells it.
                logger.warn("Identity could not say who hears of rule {}'s alerts; they are told next pass: {}", rule.id, unavailable.getMessage());
                break;
            }
            this.delivery.opened(rule, incident, to, this.incidents, now);
            told++;
        }
        pass.told += told;
        for (AlertIncidents.Incident incident : open.values()) {
            if (seen.contains(incident.subject)) {
                continue;
            }
            if (this.incidents.resolve(incident.id, "AUTO", null, now)) {
                pass.resolved++;
                List<IdentityPort.Person> to = Collections.emptyList();
                if (incident.notified != null) {
                    try {
                        to = this.delivery.recipients(rule, audience);
                    } catch (IdentityPort.Unavailable unavailable) {
                        logger.warn("Alert {} resolved; Identity could not say who to tell: {}", incident.id, unavailable.getMessage());
                    }
                }
                this.delivery.resolved(rule, incident, to, "AUTO");
            }
        }
    }

    /** What the rule finds now; empty (not an empty list) when it cannot be evaluated this pass -- nothing then changes. */
    Optional<List<Finding>> findings(AlertRule rule, Reads reads, Set<String> openSubjects, Instant now) {
        switch (rule.kind) {
            case RUN_FAILED:
                return Optional.of(RuleChecks.runFailed(rule, reads.failures, reads.lastCompleted, now));
            case SCHEDULE_MISSED:
                return Optional.of(RuleChecks.scheduleMissed(rule, reads.skips, reads.overdue, now));
            case RUN_SLOW:
                return Optional.of(RuleChecks.runSlow(rule, reads.slowCandidates, reads.usual, now));
            case REVIEW_WAITING:
                return Optional.of(RuleChecks.reviewWaiting(rule, reads.waiting, now));
            case SERVICE_UNHEALTHY:
                return reads.samplesFresh ? Optional.of(RuleChecks.serviceUnhealthy(rule, reads.recent, reads.planned)) : Optional.empty();
            case SERVICE_RESTARTED:
                return reads.samplesFresh ? Optional.of(RuleChecks.serviceRestarted(rule, reads.recent, now, reads.planned)) : Optional.empty();
            case MEMORY_HIGH:
                return reads.samplesFresh ? Optional.of(RuleChecks.memoryHigh(rule, reads.recent, openSubjects)) : Optional.empty();
            case KAFKA_LAG:
                return Optional.of(RuleChecks.kafkaLag(rule, reads.probes));
            case DISK_LOW:
                return Optional.of(RuleChecks.diskLow(rule, reads.probes));
            case BILLING_MISMATCH:
                return Optional.of(RuleChecks.billingMismatch(reads.probes));
            default:
                return Optional.empty();
        }
    }

    /** Everything the rules need, read once: per kind, for the workspaces that have such a rule, over its widest window. */
    Reads read(List<AlertRule> all, Instant now) {
        Map<AlertKind, Set<Long>> tenants = new EnumMap<>(AlertKind.class);
        Map<AlertKind, Integer> widest = new EnumMap<>(AlertKind.class);
        boolean platform = false;
        for (AlertRule rule : all) {
            if (rule.kind == null) {
                continue;
            }
            if (rule.isPlatform()) {
                platform = true;
                continue;
            }
            tenants.computeIfAbsent(rule.kind, k -> new LinkedHashSet<>()).add(rule.tenantId);
            widest.merge(rule.kind, rule.windowMinutes, Math::max);
        }
        Reads reads = new Reads();
        if (tenants.containsKey(AlertKind.RUN_FAILED)) {
            Instant since = now.minus(Duration.ofMinutes(widest.get(AlertKind.RUN_FAILED)));
            reads.failures = this.signals.failures(tenants.get(AlertKind.RUN_FAILED), since);
            Set<Long> jobs = new HashSet<>();
            reads.failures.forEach(f -> jobs.add(f.jobId));
            reads.lastCompleted = this.signals.lastCompleted(jobs, since);
        }
        if (tenants.containsKey(AlertKind.SCHEDULE_MISSED)) {
            reads.skips = this.signals.skips(tenants.get(AlertKind.SCHEDULE_MISSED),
                now.minus(Duration.ofMinutes(widest.get(AlertKind.SCHEDULE_MISSED))));
            reads.overdue = this.signals.overdue(tenants.get(AlertKind.SCHEDULE_MISSED), now);
        }
        if (tenants.containsKey(AlertKind.RUN_SLOW)) {
            Set<Long> slow = tenants.get(AlertKind.RUN_SLOW);
            List<WorkspaceSignals.Timed> candidates = new ArrayList<>(this.signals.running(slow));
            candidates.addAll(this.signals.completed(slow, now.minus(Duration.ofMinutes(widest.get(AlertKind.RUN_SLOW)))));
            reads.slowCandidates = candidates;
            reads.usual = candidates.isEmpty() ? new HashMap<>() : this.signals.usual(slow, now);
        }
        if (tenants.containsKey(AlertKind.REVIEW_WAITING)) {
            int shortest = Integer.MAX_VALUE;
            for (AlertRule rule : all) {
                if (rule.kind == AlertKind.REVIEW_WAITING && !rule.isPlatform()) {
                    shortest = Math.min(shortest, rule.windowMinutes);
                }
            }
            reads.waiting = this.signals.waiting(tenants.get(AlertKind.REVIEW_WAITING), now.minus(Duration.ofMinutes(shortest)));
        }
        if (platform) {
            reads.recent = this.health.recent(now);
            reads.probes = this.health.probes();
            Optional<Instant> newest = this.health.newestSample();
            reads.samplesFresh = newest.isPresent() && Duration.between(newest.get(), now).compareTo(STALE) <= 0;
            reads.planned = this.planned.byService(now);
        } else {
            reads.recent = Collections.emptyMap();
            reads.probes = Collections.emptyMap();
        }
        return reads;
    }

    /** Runs as the rule's owner: its workspace, or across workspaces for the platform's (tenant_id NULL). */
    private <T> T asOwner(AlertRule rule, Supplier<T> work) {
        return rule.isPlatform()
            ? RowSecurity.acrossTenants("the alert evaluator (MIG-355) writes the platform's own alerts (tenant_id NULL)", work)
            : RowSecurity.forTenant(rule.tenantId, work);
    }

    /** Open alerts of a rule someone turned off: resolved, as nothing watches them now. */
    void resolveTurnedOff(Instant now, Pass pass) {
        int closed = RowSecurity.acrossTenants("the alert evaluator (MIG-355) resolves the open alerts of every workspace's rules that were "
            + "turned off", () -> this.jdbc.update("UPDATE alert_incident SET state = 'RESOLVED', resolved_at = ?, resolved_how = 'AUTO' "
            + "WHERE state = 'OPEN' AND alert_rule_id IN (SELECT alert_rule_id FROM alert_rule WHERE NOT enabled)", Timestamp.from(now)));
        pass.resolved += closed;
    }

    /** A person resolves an alert of their scope (tenantId null: the platform's); false when it is not theirs or not open. */
    public boolean resolveByHand(long id, Long tenantId, Long by, Instant now) {
        return Boolean.TRUE.equals(this.transaction.execute(status -> {
            Optional<AlertIncidents.Incident> incident = this.incidents.find(id);
            if (!incident.isPresent() || !incident.get().isOpen() || !Objects.equals(incident.get().tenantId, tenantId)) {
                return false;
            }
            if (!this.incidents.resolve(id, "MANUAL", by, now)) {
                return false;
            }
            Optional<AlertRule> rule = this.rules.find(incident.get().ruleId, tenantId);
            if (rule.isPresent() && incident.get().notified != null) {
                try {
                    this.delivery.resolved(rule.get(), incident.get(), this.delivery.recipients(rule.get(), new AlertDelivery.Audience()), "MANUAL");
                } catch (IdentityPort.Unavailable unavailable) {
                    logger.warn("Alert {} resolved by user {}; its resolution notice was not sent: {}", id, by, unavailable.getMessage());
                }
            }
            return true;
        }));
    }
}
