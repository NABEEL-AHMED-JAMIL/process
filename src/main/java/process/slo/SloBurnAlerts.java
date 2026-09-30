package process.slo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.barco.platform.tenancy.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.enums.UserRole;
import process.notifications.NotificationPort;
import process.notifications.Notices;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The error-budget burn alert (MIG-196; etl-platform docs/SLO.md section 3), without Prometheus: every five minutes,
 * under ShedLock so one replica checks, SloBurnRates evaluates both SLOs under the four multiwindow rules from stored
 * rows, and a rule that holds becomes a notification-centre notice to every platform administrator -- through Core's
 * one door to Notifications, the outbox (NotificationPort), as every other notice Core sends.
 *
 * At most one alert per SLO per rule per long window: the first alert holds a row of the shedlock table named for the
 * pair until the rule's long window (1 h, 6 h, 1 d, 3 d) has passed -- ShedLock's own row shape and rule (taken when
 * lock_until has passed), written in one statement so it does not depend on ShedLock's Java API -- so a restart or
 * another replica does not repeat it. The notice's type is JOB_FAILED (a page) or JOB_FAILED at WARNING (a ticket): notifications-service drops
 * a type it does not know, as NoticePipelineNotifier notes; an SLO type of its own needs that service's enum first.
 *
 * Measuring only: nothing here changes how a run or an event is counted.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class SloBurnAlerts {

    public static final String ALERTS = "process.slo.burn.alerts";
    /** The console page a notice links to. */
    static final String LINK = "/administration/reliability";

    private static final Logger logger = LoggerFactory.getLogger(SloBurnAlerts.class);

    /** Whether this is the first alert for a name within its hold; taking it when it is. */
    interface Once {
        boolean first(String name, Duration holdFor);
    }

    /** One upsert: a new name is inserted; an existing one is taken only once its lock_until has passed. */
    static final String TAKE = "insert into shedlock (name, lock_until, locked_at, locked_by) "
        + "values (?, now() + make_interval(secs => cast(? as double precision)), now(), ?) on conflict (name) do update "
        + "set lock_until = excluded.lock_until, locked_at = excluded.locked_at, locked_by = excluded.locked_by "
        + "where shedlock.lock_until <= now()";

    private final SloBurnRates rates;
    private final Once once;
    private final IdentityPort identity;
    private final NotificationPort notifications;
    private final MeterRegistry registry;
    private final boolean enabled;

    @Autowired
    public SloBurnAlerts(SloBurnRates rates, JdbcTemplate jdbc, IdentityPort identity, NotificationPort notifications,
        MeterRegistry registry, @Value("${process.slo.alerts.enabled:true}") boolean enabled) {
        this(rates, shedlockTable(jdbc), identity, notifications, registry, enabled);
    }

    SloBurnAlerts(SloBurnRates rates, Once once, IdentityPort identity, NotificationPort notifications,
        MeterRegistry registry, boolean enabled) {
        this.rates = rates;
        this.once = once;
        this.identity = identity;
        this.notifications = notifications;
        this.registry = registry;
        this.enabled = enabled;
    }

    static Once shedlockTable(JdbcTemplate jdbc) {
        String me = host();
        return (name, holdFor) -> jdbc.update(TAKE, name, holdFor.getSeconds(), me) == 1;
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception unknown) {
            return "process";
        }
    }

    /** Every five minutes, off the other monitors' minutes; a failure is logged and the next tick tries again. */
    @Scheduled(cron = "${process.slo.alerts.cron:0 3/5 * * * *}")
    @SchedulerLock(name = "sloBurnAlerts", lockAtLeastFor = "30S", lockAtMostFor = "4M")
    public void check() {
        if (!this.enabled) {
            return;
        }
        try {
            this.checkOnce();
        } catch (RuntimeException failed) {
            logger.warn("Could not check the SLO error budgets: {}", failed.getMessage());
        }
    }

    /** The alerts this check sent, one per (SLO, rule) that holds and was not already alerted in its long window. */
    List<SloBurnRates.Evaluation> checkOnce() {
        List<SloBurnRates.Evaluation> sent = new ArrayList<>();
        List<IdentityPort.Person> admins = null;
        for (SloBurnRates.Evaluation evaluation : this.rates.evaluate()) {
            if (!evaluation.firing()) {
                continue;
            }
            if (admins == null) {
                // Asked before the lock is taken: Identity unreachable means the alert waits for the next tick, not lost.
                admins = this.platformAdmins();
            }
            if (!this.once.first(lockName(evaluation), evaluation.rule.longWindow)) {
                continue;
            }
            this.send(evaluation, admins);
            sent.add(evaluation);
        }
        return sent;
    }

    static String lockName(SloBurnRates.Evaluation evaluation) {
        return "sloBurn:" + evaluation.sli.key() + ":" + evaluation.rule.name();
    }

    private List<IdentityPort.Person> platformAdmins() {
        List<IdentityPort.Person> admins = new ArrayList<>();
        for (IdentityPort.Person person : this.identity.members(TenantScope.of(null, UserRole.PLATFORM_ADMIN.name(), null))) {
            if (!person.isDeleted() && person.getAppUserId() != null && UserRole.PLATFORM_ADMIN.name().equals(person.getUserRole())) {
                admins.add(person);
            }
        }
        return admins;
    }

    private void send(SloBurnRates.Evaluation evaluation, List<IdentityPort.Person> admins) {
        boolean page = evaluation.rule.severity == SloBurnRates.Severity.PAGE;
        String title = evaluation.sli.title() + " is burning its error budget (" + times(evaluation.rule.threshold) + ")";
        String body = body(evaluation);
        logger.warn("SLO burn alert, {} {}: {}", evaluation.sli.key(), evaluation.rule, body);
        for (IdentityPort.Person admin : admins) {
            this.notifications.notificationCreated(admin.getTenantId(), Notices.notice(admin.getAppUserId(), NotificationType.JOB_FAILED,
                page ? NotificationSeverity.ERROR : NotificationSeverity.WARNING, title, body, LINK));
        }
        Counter.builder(ALERTS).tag("sli", evaluation.sli.key()).tag("rule", evaluation.rule.name().toLowerCase(Locale.ROOT))
            .description("Error-budget burn alerts sent to platform administrators").register(this.registry).increment();
        if (admins.isEmpty()) {
            logger.warn("No platform administrator to tell about the {} burn alert.", evaluation.sli.key());
        }
    }

    static String body(SloBurnRates.Evaluation evaluation) {
        SloBurnRates.Burn longBurn = evaluation.longBurn;
        SloBurnRates.Burn shortBurn = evaluation.shortBurn;
        double days = SloBurnRates.SLO_WINDOW.toHours() / 24.0 / longBurn.rate;
        return String.format(Locale.ROOT, "%s bad of %s counted in the last %s (%s burn), %s of %s in the last %s (%s). "
                + "At this rate the %s-day error budget of the %.2f%% target is spent in %s. %s rule; not repeated for %s.",
            longBurn.bad, longBurn.counted(), SloBurnRates.human(longBurn.window), times(longBurn.rate),
            shortBurn.bad, shortBurn.counted(), SloBurnRates.human(shortBurn.window), times(shortBurn.rate),
            SloBurnRates.SLO_WINDOW.toDays(), evaluation.sli.target() * 100, spentIn(days),
            evaluation.rule.severity == SloBurnRates.Severity.PAGE ? "Page" : "Ticket", SloBurnRates.human(evaluation.rule.longWindow));
    }

    private static String times(double rate) {
        return String.format(Locale.ROOT, "%.1fx", rate);
    }

    private static String spentIn(double days) {
        if (days >= 1) {
            return String.format(Locale.ROOT, "%.1f days", days);
        }
        double hours = days * 24;
        return hours >= 1 ? String.format(Locale.ROOT, "%.1f hours", hours)
            : String.format(Locale.ROOT, "%d minutes", Math.max(1, Math.round(hours * 60)));
    }
}
