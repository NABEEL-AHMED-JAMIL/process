package process.customer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import process.forms.FormWorkflows;
import process.model.dto.ResponseDto;
import process.pipeline.PipelineDefinition;
import process.security.TenantContext;
import process.util.BusinessTime;
import process.util.ProcessUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A workspace's event routes (MIG-332, ADR-025 decision 3, "Events in"), its administrators' (Integration › API clients ›
 * Event routes): the organisation names its own event types, and each route says what one starts -- a pipeline or a
 * workflow -- optionally checking the event's data against a data contract first. POST /v1/events follows them. Generic:
 * nothing here knows any type.
 */
@Service
public class EventRoutes {

    /** An event type: a letter or digit, then up to 127 of letters, digits and . _ : - ("order.received"). */
    public static final Pattern TYPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    public static final String PIPELINE = "PIPELINE";
    public static final String WORKFLOW = "WORKFLOW";
    static final int MAX_ROUTES = 200;

    private final EventRouteStore store;
    private final JdbcTemplate sql;
    private final FormWorkflows workflows;
    private final InputContracts contracts;

    public EventRoutes(EventRouteStore store, JdbcTemplate sql, FormWorkflows workflows, InputContracts contracts) {
        this.store = store;
        this.sql = sql;
        this.workflows = workflows;
        this.contracts = contracts;
    }

    /** The workspace's routes, with the pipeline's name for a pipeline route. */
    public ResponseDto list() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return new ResponseDto(ProcessUtil.ERROR, "Event routes belong to a workspace.");
        }
        Map<Long, String> names = this.pipelineNames(tenantId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EventRouteStore.Route route : this.store.list(tenantId)) {
            rows.add(view(route, names));
        }
        return new ResponseDto(ProcessUtil.SUCCESS, "Event routes.", rows);
    }

    /** The pipelines a route may start: the workspace's jobs that are not deleted, newest first. */
    public ResponseDto targets() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return new ResponseDto(ProcessUtil.ERROR, "Event routes belong to a workspace.");
        }
        List<Map<String, Object>> pipelines = new ArrayList<>();
        this.pipelineNames(tenantId).forEach((id, name) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("jobId", id);
            one.put("name", name);
            pipelines.add(one);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pipelines", pipelines);
        return new ResponseDto(ProcessUtil.SUCCESS, "Targets.", data);
    }

    /** A new route (no routeId) or a change to one; each field checked, the target and the contract the workspace's own. */
    public ResponseDto save(EventRouteRequest request) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return new ResponseDto(ProcessUtil.ERROR, "Event routes belong to a workspace.");
        }
        if (request == null) {
            return new ResponseDto(ProcessUtil.ERROR, "Name the event type and what it starts.");
        }
        EventRouteStore.Route route = new EventRouteStore.Route();
        route.tenantId = tenantId;
        String refused = this.fill(route, request, tenantId);
        if (refused != null) {
            return new ResponseDto(ProcessUtil.ERROR, refused);
        }
        if (request.getRouteId() == null) {
            if (this.store.list(tenantId).size() >= MAX_ROUTES) {
                return new ResponseDto(ProcessUtil.ERROR, "A workspace has at most " + MAX_ROUTES + " event routes.");
            }
            route.createdBy = TenantContext.getAppUserId();
            route.routeId = this.store.insert(route);
        } else {
            route.routeId = request.getRouteId();
            route.updatedBy = TenantContext.getAppUserId();
            if (!this.store.update(route)) {
                return new ResponseDto(ProcessUtil.ERROR, "No such event route in this workspace.");
            }
        }
        EventRouteStore.Route saved = this.store.find(tenantId, route.routeId).orElseThrow(() -> new IllegalStateException("The route is gone."));
        return new ResponseDto(ProcessUtil.SUCCESS, "Event route saved.", view(saved, this.pipelineNames(tenantId)));
    }

    public ResponseDto delete(Long routeId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || routeId == null) {
            return new ResponseDto(ProcessUtil.ERROR, "Name the event route.");
        }
        return this.store.delete(tenantId, routeId) ? new ResponseDto(ProcessUtil.SUCCESS, "Event route removed.")
            : new ResponseDto(ProcessUtil.ERROR, "No such event route in this workspace.");
    }

    /** The request's fields into the route; why not, or null. */
    private String fill(EventRouteStore.Route route, EventRouteRequest request, long tenantId) {
        String type = request.getEventType() == null ? "" : request.getEventType().trim();
        if (!TYPE.matcher(type).matches()) {
            return "An event type is 1 to 128 letters, digits and . _ : - (for example order.received).";
        }
        route.eventType = type;
        String kind = request.getTargetKind() == null ? "" : request.getTargetKind().trim().toUpperCase(Locale.ROOT);
        if (PIPELINE.equals(kind)) {
            if (request.getJobId() == null || !this.pipelineNames(tenantId).containsKey(request.getJobId())) {
                return "Choose a pipeline of this workspace.";
            }
            route.jobId = request.getJobId();
        } else if (WORKFLOW.equals(kind)) {
            String key = request.getWorkflowKey() == null ? "" : request.getWorkflowKey().trim();
            if (key.isEmpty() || key.length() > 128) {
                return "Name the workflow by its key.";
            }
            Optional<FormWorkflows.Workflow> workflow;
            try {
                workflow = this.workflows.find(tenantId, key);
            } catch (RuntimeException unreachable) {
                return "Workflows cannot be checked right now (" + unreachable.getMessage() + "). Try again in a moment.";
            }
            if (!workflow.isPresent()) {
                return "No workflow with the key " + key + " in this workspace.";
            }
            route.workflowKey = key;
        } else {
            return "A route starts a PIPELINE or a WORKFLOW.";
        }
        route.targetKind = kind;
        boolean byId = request.getContractId() != null;
        boolean byName = request.getContractName() != null && !request.getContractName().trim().isEmpty();
        if (byId && byName) {
            return "Name the contract by its id or by its name, not both.";
        }
        if (request.getContractVersion() != null && request.getContractVersion() < 1) {
            return "A contract version is at least 1.";
        }
        if (byId || byName) {
            route.contractId = request.getContractId();
            route.contractName = byName ? request.getContractName().trim() : null;
            route.contractVersion = request.getContractVersion();
            try {
                this.contracts.check(tenantId, PipelineDefinition.ContractRef.of(route.contractId, route.contractName, route.contractVersion),
                    null, "data");
            } catch (InputContracts.Unavailable unavailable) {
                return "The contract cannot be used: " + unavailable.getMessage();
            }
        }
        route.status = request.getActive() == null || request.getActive() ? "Active" : "Inactive";
        return null;
    }

    private Map<Long, String> pipelineNames(long tenantId) {
        Map<Long, String> names = new LinkedHashMap<>();
        this.sql.query("SELECT job_id, job_name FROM source_job WHERE tenant_id = ? AND job_status <> 'Delete' ORDER BY job_id DESC",
            rs -> {
                names.put(rs.getLong(1), rs.getString(2));
            }, tenantId);
        return names;
    }

    static Map<String, Object> view(EventRouteStore.Route route, Map<Long, String> pipelineNames) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("routeId", route.routeId);
        view.put("eventType", route.eventType);
        view.put("targetKind", route.targetKind);
        view.put("jobId", route.jobId);
        view.put("pipelineName", route.jobId == null ? null : pipelineNames.get(route.jobId));
        view.put("workflowKey", route.workflowKey);
        view.put("contractId", route.contractId);
        view.put("contractName", route.contractName);
        view.put("contractVersion", route.contractVersion);
        view.put("active", "Active".equals(route.status));
        view.put("createdBy", route.createdBy);
        view.put("dateCreated", route.dateCreated == null ? null : BusinessTime.wallClockOf(route.dateCreated.toInstant()).toString());
        view.put("dateUpdated", route.dateUpdated == null ? null : BusinessTime.wallClockOf(route.dateUpdated.toInstant()).toString());
        return view;
    }
}
