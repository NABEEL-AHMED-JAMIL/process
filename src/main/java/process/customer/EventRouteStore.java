package process.customer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * event_route (MIG-332, V201) in plain SQL: a workspace's event types and what each starts. Under row security as the
 * caller; every statement also names the workspace.
 */
@Repository
public class EventRouteStore {

    /** One route. */
    public static final class Route {
        public long routeId;
        public long tenantId;
        public String eventType;
        /** PIPELINE or WORKFLOW. */
        public String targetKind;
        public Long jobId;
        public String workflowKey;
        public Long contractId;
        public String contractName;
        public Integer contractVersion;
        public String status;
        public Long createdBy;
        public Timestamp dateCreated;
        public Long updatedBy;
        public Timestamp dateUpdated;

        public boolean hasContract() {
            return this.contractId != null || (this.contractName != null && !this.contractName.trim().isEmpty());
        }
    }

    private static final String COLUMNS = "route_id, tenant_id, event_type, target_kind, job_id, workflow_key, contract_id, contract_name, "
        + "contract_version, status, created_by, date_created, updated_by, date_updated";

    private static final RowMapper<Route> ROW = (rs, n) -> {
        Route route = new Route();
        route.routeId = rs.getLong("route_id");
        route.tenantId = rs.getLong("tenant_id");
        route.eventType = rs.getString("event_type");
        route.targetKind = rs.getString("target_kind");
        route.jobId = (Long) rs.getObject("job_id");
        route.workflowKey = rs.getString("workflow_key");
        route.contractId = (Long) rs.getObject("contract_id");
        route.contractName = rs.getString("contract_name");
        route.contractVersion = (Integer) rs.getObject("contract_version");
        route.status = rs.getString("status");
        route.createdBy = (Long) rs.getObject("created_by");
        route.dateCreated = rs.getTimestamp("date_created");
        route.updatedBy = (Long) rs.getObject("updated_by");
        route.dateUpdated = rs.getTimestamp("date_updated");
        return route;
    };

    private final JdbcTemplate sql;

    public EventRouteStore(JdbcTemplate sql) {
        this.sql = sql;
    }

    public List<Route> list(long tenantId) {
        return this.sql.query("SELECT " + COLUMNS + " FROM event_route WHERE tenant_id = ? ORDER BY event_type, route_id", ROW, tenantId);
    }

    /** The active routes of one event type, oldest first: the order an event starts them in. */
    public List<Route> activeFor(long tenantId, String eventType) {
        return this.sql.query("SELECT " + COLUMNS + " FROM event_route WHERE tenant_id = ? AND event_type = ? AND status = 'Active' "
            + "ORDER BY route_id", ROW, tenantId, eventType);
    }

    public Optional<Route> find(long tenantId, long routeId) {
        return this.sql.query("SELECT " + COLUMNS + " FROM event_route WHERE tenant_id = ? AND route_id = ?", ROW, tenantId, routeId).stream()
            .findFirst();
    }

    public long insert(Route route) {
        Long id = this.sql.queryForObject("INSERT INTO event_route (tenant_id, event_type, target_kind, job_id, workflow_key, contract_id, "
            + "contract_name, contract_version, status, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING route_id", Long.class,
            route.tenantId, route.eventType, route.targetKind, route.jobId, route.workflowKey, route.contractId, route.contractName,
            route.contractVersion, route.status, route.createdBy);
        return id == null ? 0L : id;
    }

    public boolean update(Route route) {
        return this.sql.update("UPDATE event_route SET event_type = ?, target_kind = ?, job_id = ?, workflow_key = ?, contract_id = ?, "
            + "contract_name = ?, contract_version = ?, status = ?, updated_by = ?, date_updated = now() WHERE tenant_id = ? AND route_id = ?",
            route.eventType, route.targetKind, route.jobId, route.workflowKey, route.contractId, route.contractName, route.contractVersion,
            route.status, route.updatedBy, route.tenantId, route.routeId) == 1;
    }

    public boolean delete(long tenantId, long routeId) {
        return this.sql.update("DELETE FROM event_route WHERE tenant_id = ? AND route_id = ?", tenantId, routeId) == 1;
    }

    /** An event received: its id. */
    public long recordEvent(long tenantId, String eventType, String clientId) {
        Long id = this.sql.queryForObject("INSERT INTO api_event (tenant_id, event_type, client_id) VALUES (?, ?, ?) RETURNING event_id",
            Long.class, tenantId, eventType, clientId);
        return id == null ? 0L : id;
    }

    /** What the event started, as written for the record (run and workflow ids; never its data). */
    public void eventStarted(long tenantId, long eventId, String started) {
        this.sql.update("UPDATE api_event SET started = ? WHERE tenant_id = ? AND event_id = ?", started, tenantId, eventId);
    }
}
