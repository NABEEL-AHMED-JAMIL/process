package process.monitoring;

import org.barco.notifications.contract.MailRequested;
import org.barco.platform.tenancy.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.enums.UserRole;
import process.notifications.MailExtras;
import process.notifications.NotificationPort;
import process.notifications.Notices;
import process.util.BusinessTime;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MIG-355: telling people (and machines) of an alert -- through Core's one door to Notifications (the outbox), in the
 * transaction that opened or resolved it, so an alert is never told twice and never lost:
 *
 * <ul>
 *   <li><b>The bell</b>: one notice (type ALERT) to each recipient when it opens, and one when it resolves.</li>
 *   <li><b>Mail</b>: one ALERT mail to each recipient when it opens (notifications-service sends at most one per
 *   alert and person: the dedupe key). Held by the rule's quiet hours, and sent when they end if the alert is still open.
 *   Not sent when it resolves.</li>
 *   <li><b>Webhook</b>: alert.opened and alert.resolved through the customer event journal (api_event_out kind 'alert'),
 *   to the workspace's webhooks that subscribe to them -- signed and guarded by integration-service.</li>
 * </ul>
 *
 * Who: a workspace rule's roles (TENANT_ADMIN, TENANT_USER) and groups (members of the named access profiles), never
 * deleted people, and only the rule's own workspace's people -- members(TenantScope.tenant(id)) cannot answer another's.
 * A platform rule tells every platform administrator.
 */
@Component
public class AlertDelivery {

    private static final Logger logger = LoggerFactory.getLogger(AlertDelivery.class);
    static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private final NotificationPort notifications;
    private final IdentityPort identity;
    private final JdbcTemplate jdbc;
    private final String consoleUrl;

    @Autowired
    public AlertDelivery(NotificationPort notifications, IdentityPort identity, JdbcTemplate jdbc,
        @Value("${customer.embed.console-url:http://localhost:4400}") String consoleUrl) {
        this.notifications = notifications;
        this.identity = identity;
        this.jdbc = jdbc;
        this.consoleUrl = consoleUrl == null ? "" : consoleUrl.trim().replaceAll("/+$", "");
    }

    /** The people a pass has already asked Identity for, by workspace (null: the platform's administrators). */
    public static final class Audience {
        private final Map<Long, List<IdentityPort.Person>> byWorkspace = new HashMap<>();
        private List<IdentityPort.Person> platform;
    }

    /** Who hears of this rule's alerts; throws IdentityPort.Unavailable when Identity cannot say (the next pass tries again). */
    public List<IdentityPort.Person> recipients(AlertRule rule, Audience audience) {
        List<IdentityPort.Person> out = new ArrayList<>();
        if (rule.isPlatform()) {
            if (audience.platform == null) {
                List<IdentityPort.Person> admins = new ArrayList<>();
                for (IdentityPort.Person person : this.identity.members(TenantScope.of(null, UserRole.PLATFORM_ADMIN.name(), null))) {
                    if (!person.isDeleted() && person.getAppUserId() != null && UserRole.PLATFORM_ADMIN.name().equals(person.getUserRole())) {
                        admins.add(person);
                    }
                }
                audience.platform = admins;
            }
            return audience.platform;
        }
        List<IdentityPort.Person> members = audience.byWorkspace.computeIfAbsent(rule.tenantId,
            id -> this.identity.members(TenantScope.tenant(id)));
        for (IdentityPort.Person person : members) {
            if (person.isDeleted() || person.getAppUserId() == null || !rule.tenantId.equals(person.getTenantId())) {
                continue;
            }
            boolean byRole = person.getUserRole() != null && rule.roles.contains(person.getUserRole());
            boolean byGroup = person.getPageAccessProfileId() != null && rule.groups.contains(person.getPageAccessProfileId());
            if (byRole || byGroup) {
                out.add(person);
            }
        }
        return out;
    }

    /** An alert opened (or one whose telling failed before): the bell, mail (unless quiet) and the webhook. */
    public void opened(AlertRule rule, AlertIncidents.Incident incident, List<IdentityPort.Person> to, AlertIncidents incidents, Instant now) {
        Long tenant = rule.tenantId;
        NotificationSeverity severity = rule.critical() || "CRITICAL".equals(incident.severity) ? NotificationSeverity.ERROR
            : NotificationSeverity.WARNING;
        if (rule.bell) {
            for (IdentityPort.Person person : to) {
                this.notifications.notificationCreated(tenant, Notices.notice(person.getAppUserId(), NotificationType.ALERT, severity,
                    AlertIncidents.cut(incident.title, 300), body(incident, rule), incident.link));
            }
        }
        if (rule.email) {
            if (QuietHours.quiet(rule, now)) {
                incidents.emailHeld(incident.id, true);
            } else {
                this.mail(rule, incident, to);
                incidents.emailed(incident.id, now);
            }
        }
        if (rule.webhook && tenant != null) {
            this.journal(tenant, incident.id, "opened");
        }
        incidents.notified(incident.id, now);
        logger.info("Alert {} opened by rule {} ({}{}): {}; told {} people{}{}.", incident.id, rule.id, rule.kind,
            tenant == null ? ", platform" : ", workspace " + tenant, incident.title, to.size(), rule.email ? ", mail" : "",
            rule.webhook ? ", webhook" : "");
    }

    /** Mail the quiet hours held, now they are over. */
    public void heldMail(AlertRule rule, AlertIncidents.Incident incident, List<IdentityPort.Person> to, AlertIncidents incidents, Instant now) {
        this.mail(rule, incident, to);
        incidents.emailed(incident.id, now);
        logger.info("Alert {}: the mail quiet hours held went to {} people.", incident.id, to.size());
    }

    /** An alert resolved: a bell notice that says so, and alert.resolved to the webhooks. */
    public void resolved(AlertRule rule, AlertIncidents.Incident incident, List<IdentityPort.Person> to, String how) {
        if (rule.bell && incident.notified != null) {
            for (IdentityPort.Person person : to) {
                this.notifications.notificationCreated(rule.tenantId, Notices.notice(person.getAppUserId(), NotificationType.ALERT,
                    NotificationSeverity.SUCCESS, AlertIncidents.cut("Resolved: " + incident.title, 300),
                    "AUTO".equals(how) ? "It cleared by itself; nothing more is needed unless it comes back." : "A person marked it resolved.",
                    incident.link));
            }
        }
        if (rule.webhook && rule.tenantId != null && incident.notified != null) {
            this.journal(rule.tenantId, incident.id, "resolved");
        }
    }

    private void mail(AlertRule rule, AlertIncidents.Incident incident, List<IdentityPort.Person> to) {
        boolean critical = "CRITICAL".equals(incident.severity);
        for (IdentityPort.Person person : to) {
            String address = person.getUsername();
            if (address == null || !address.contains("@")) {
                continue;
            }
            MailRequested mail = new MailRequested().setTemplate(MailRequested.Template.ALERT).setRecipient(address)
                .setSubject(AlertIncidents.cut((critical ? "[Alert, critical] " : "[Alert] ") + incident.title, 200))
                .put("headline", html(incident.title))
                .put("state_label", critical ? "Alert, critical" : "Alert")
                .put("accent", critical ? "#dc2626" : "#d97706")
                .put("detail", html(incident.detail == null ? "" : incident.detail))
                .put("rule_name", html(rule.name))
                .put("scope_label", html(rule.isPlatform() ? "The platform" : "Your workspace"))
                .put("since_label", html(SINCE.format(BusinessTime.wallClockOf(incident.firstSeen)) + " (Chicago)"))
                .put("runbook", html(AlertKind.of(incident.kind).map(AlertKind::whatToDo).orElse("")))
                .put("link", html(this.consoleUrl + (incident.link == null ? "/" : incident.link)))
                .setDedupeKey("alert:" + incident.id + ":" + person.getAppUserId());
            this.notifications.mailRequested(rule.tenantId, mail, MailExtras.NONE);
        }
    }

    /** alert.opened or alert.resolved in the workspace's customer event journal; CustomerEventRelay sends it. */
    void journal(long tenantId, long incidentId, String event) {
        this.jdbc.update("INSERT INTO api_event_out (tenant_id, kind, alert_incident_id, alert_event) VALUES (?, 'alert', ?, ?)",
            tenantId, incidentId, event);
    }

    /** What the bell shows under the title: what was found, then what to do. */
    static String body(AlertIncidents.Incident incident, AlertRule rule) {
        String what = incident.detail == null ? "" : incident.detail.trim();
        String todo = rule.kind == null ? "" : rule.kind.whatToDo();
        String joined = what.isEmpty() ? todo : what + (todo.isEmpty() ? "" : " What to do: " + todo);
        return AlertIncidents.cut(joined, 1000);
    }

    /** Text into the mail's HTML: every value is escaped here, so an error naming a step <like_this> reads as text. */
    static String html(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (char c : text.toCharArray()) {
            out.append(escaped(c));
        }
        return out.toString();
    }

    private static String escaped(char c) {
        switch (c) {
            case '<':
                return "&lt;";
            case '>':
                return "&gt;";
            case '&':
                return "&amp;";
            case '"':
                return "&quot;";
            case '\'':
                return "&#39;";
            default:
                return String.valueOf(c);
        }
    }
}
