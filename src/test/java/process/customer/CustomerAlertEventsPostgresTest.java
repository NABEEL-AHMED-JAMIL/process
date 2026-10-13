package process.customer;

import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.forms.FormStore;
import process.outbox.OutboxWriter;
import process.pipeline.StepStore;
import process.pipeline.review.RunReviews;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-355: an alert in the customer event journal (api_event_out kind 'alert') becomes alert.opened or alert.resolved, its
 * data in the catalogue's shape (ids as strings, UTC times, lower-case words), as its own workspace only.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL.
 */
class CustomerAlertEventsPostgresTest {

    static final long A = 8871L;
    static final long B = 8872L;
    static final long JOB = 887101L;

    static ScratchPostgres db;
    static long incident;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("customer_alerts");
        JdbcTemplate login = db.jdbc();
        for (long t : new long[]{A, B}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", t, "t" + t, "T" + t);
        }
        login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Manual', 'Claims (synthetic)', 'Active', 1, ?)", JOB, A);
        Long rule = login.queryForObject("INSERT INTO alert_rule (tenant_id, kind, name, notify_webhook) VALUES (?, 'RUN_FAILED', 'Any failure', true) "
            + "RETURNING alert_rule_id", Long.class, A);
        incident = login.queryForObject("INSERT INTO alert_incident (tenant_id, alert_rule_id, kind, subject, severity, title, detail, job_id, "
            + "job_queue_id, occurrences) VALUES (?, ?, 'RUN_FAILED', 'pipeline:887101', 'CRITICAL', 'Claims (synthetic) failed', 'Run 5 failed.', ?, 5, 2) "
            + "RETURNING alert_incident_id", Long.class, A, rule, JOB);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    static CustomerEventRelay.Pending pending(long tenant, String event) {
        CustomerEventRelay.Pending row = new CustomerEventRelay.Pending();
        row.outId = 1;
        row.tenantId = tenant;
        row.kind = "alert";
        row.alertIncidentId = incident;
        row.alertEvent = event;
        return row;
    }

    @Test
    void anAlertBecomesItsCatalogueEventAsItsOwnWorkspaceOnly() {
        CustomerEventRelay relay = CustomerEventRelayAccess.relay(db.appJdbc(), db.transactions(), mock(OutboxWriter.class), mock(CustomerRuns.class),
            mock(RunReviews.class), mock(StepStore.class), mock(FormStore.class));
        List<CustomerEventRelay.Event> opened = RowSecurity.forTenant(A, () -> relay.eventsOf(pending(A, "opened")));
        assertThat(opened).hasSize(1);
        assertThat(opened.get(0).type).isEqualTo("alert.opened");
        assertThat(opened.get(0).subject).isEqualTo("alert:" + incident);
        Map<String, Object> data = opened.get(0).data;
        assertThat(data).containsEntry("alertId", String.valueOf(incident)).containsEntry("kind", "run_failed").containsEntry("severity", "critical")
            .containsEntry("state", "open").containsEntry("pipelineId", String.valueOf(JOB)).containsEntry("runId", "5")
            .containsEntry("ruleName", "Any failure").containsEntry("occurrences", 2).containsEntry("resolvedAt", null);
        assertThat(String.valueOf(data.get("firstSeenAt"))).endsWith("Z");

        db.jdbc().update("UPDATE alert_incident SET state = 'RESOLVED', resolved_at = now(), resolved_how = 'MANUAL' WHERE alert_incident_id = ?",
            incident);
        List<CustomerEventRelay.Event> resolved = RowSecurity.forTenant(A, () -> relay.eventsOf(pending(A, "resolved")));
        assertThat(resolved.get(0).type).isEqualTo("alert.resolved");
        assertThat(resolved.get(0).data).containsEntry("state", "resolved").containsEntry("resolvedHow", "manual");

        // A row naming another workspace's alert reads nothing: the journal row's workspace decides.
        assertThat(RowSecurity.forTenant(B, () -> relay.eventsOf(pending(B, "opened")))).isEmpty();
    }
}
