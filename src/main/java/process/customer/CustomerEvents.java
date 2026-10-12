package process.customer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.api.IdempotencyKeys;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.forms.FormWorkflows;
import process.pipeline.PipelineDefinition;
import process.security.TenantContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * POST /v1/events (MIG-332, ADR-025 decision 3; OpenAPI sendEvent): one inbound endpoint for every event type. The
 * organisation's event type names the workspace's event routes (EventRoutes); the event's data is checked against each
 * route's contract first, and nothing is stored or started when it fails (422, each field's path under "data"). Then the
 * event is recorded and each route starts what it names -- a pipeline's run with the data as its record (RunIntake), or
 * a workflow request whose subject is the event -- and the answer says what started and what did not, and why. 202.
 * Idempotent per client: the same key and body within 24 hours start nothing more. Scope events:write.
 */
@Service
public class CustomerEvents {

    static final String INSTANCE = "/v1/events";

    private static final Logger logger = LoggerFactory.getLogger(CustomerEvents.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EventRouteStore routes;
    private final PipelineCatalogue catalogue;
    private final InputContracts contracts;
    private final RunIntake intake;
    private final FormWorkflows workflows;
    private final Idempotency idempotency;

    public CustomerEvents(EventRouteStore routes, PipelineCatalogue catalogue, InputContracts contracts, RunIntake intake,
        FormWorkflows workflows, Idempotency idempotency) {
        this.routes = routes;
        this.catalogue = catalogue;
        this.contracts = contracts;
        this.intake = intake;
        this.workflows = workflows;
        this.idempotency = idempotency;
    }

    public CustomerAnswer send(byte[] body, String idempotencyKey) {
        if (!TenantContext.hasScope(ApiScopes.EVENTS_WRITE)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.EVENTS_WRITE), INSTANCE);
        }
        byte[] sent = body == null ? new byte[0] : body;
        if (sent.length > CustomerPipelines.MAX_BODY_BYTES) {
            return CustomerAnswer.problem(Problem.of(413, "An event is at most 1 MB of JSON."), INSTANCE);
        }
        String fingerprint = IdempotencyKeys.fingerprint("POST".getBytes(StandardCharsets.UTF_8), INSTANCE.getBytes(StandardCharsets.UTF_8),
            sent);
        return this.idempotency.once(idempotencyKey, INSTANCE, fingerprint, () -> this.receive(sent));
    }

    private CustomerAnswer receive(byte[] body) {
        JsonNode event;
        try {
            event = body.length == 0 ? null : JSON.readTree(body);
        } catch (IOException unreadable) {
            return CustomerAnswer.problem(Problem.of(400, "The request is not JSON."), INSTANCE);
        }
        if (event == null || !event.isObject()) {
            return CustomerAnswer.problem(Problem.of(400, "The request is a JSON object: {type, data}."), INSTANCE);
        }
        List<Problem.FieldError> errors = new ArrayList<>();
        JsonNode type = event.get("type");
        if (type == null || !type.isTextual() || !EventRoutes.TYPE.matcher(type.asText()).matches()) {
            errors.add(new Problem.FieldError("type", "an event type: 1 to 128 letters, digits and . _ : -"));
        }
        JsonNode data = event.get("data");
        if (data == null || !data.isObject()) {
            errors.add(new Problem.FieldError("data", "must be an object"));
        }
        if (!errors.isEmpty()) {
            return CustomerAnswer.problem(Problem.validation("The event does not say what it is.", errors), INSTANCE);
        }
        long tenantId = TenantContext.getTenantId();
        String eventType = type.asText();
        List<EventRouteStore.Route> routes = this.routes.activeFor(tenantId, eventType);
        if (routes.isEmpty()) {
            return CustomerAnswer.problem(Problem.validation("No event route of this workspace takes this type.",
                Collections.singletonList(new Problem.FieldError("type", "no event route takes this type"))), INSTANCE);
        }
        Map<String, Boolean> checked = new LinkedHashMap<>();
        for (EventRouteStore.Route route : routes) {
            if (!route.hasContract()) {
                continue;
            }
            String key = route.contractId + "|" + route.contractName + "|" + route.contractVersion;
            if (checked.put(key, Boolean.TRUE) != null) {
                continue;
            }
            InputContracts.Checked verdict;
            try {
                verdict = this.contracts.check(tenantId, PipelineDefinition.ContractRef.of(route.contractId, route.contractName,
                    route.contractVersion), data, "data");
            } catch (InputContracts.Unavailable unavailable) {
                return CustomerAnswer.problem(Problem.of(503, "The event's contract cannot be read right now. Try again in a moment."), INSTANCE);
            }
            if (Boolean.FALSE.equals(verdict.valid)) {
                for (Problem.FieldError error : verdict.errors) {
                    if (errors.stream().noneMatch(e -> e.getPath().equals(error.getPath()) && e.getMessage().equals(error.getMessage()))) {
                        errors.add(error);
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            return CustomerAnswer.problem(Problem.validation("The event's data does not meet its contract.", errors), INSTANCE);
        }
        String clientId = TenantContext.getClientId();
        long eventId = this.routes.recordEvent(tenantId, eventType, clientId);
        List<Map<String, Object>> started = new ArrayList<>();
        List<Map<String, Object>> workflowsStarted = new ArrayList<>();
        List<Map<String, Object>> notStarted = new ArrayList<>();
        for (EventRouteStore.Route route : routes) {
            if (EventRoutes.PIPELINE.equals(route.targetKind)) {
                this.startPipeline(route, tenantId, eventId, eventType, data, clientId, started, notStarted);
            } else {
                this.startWorkflow(route, tenantId, eventId, eventType, data, workflowsStarted, notStarted);
            }
        }
        this.routes.eventStarted(tenantId, eventId, summary(started, workflowsStarted, notStarted));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("eventId", String.valueOf(eventId));
        answer.put("started", started);
        answer.put("workflows", workflowsStarted);
        answer.put("notStarted", notStarted);
        logger.info("API event {} ({}) from client {}: {} run(s), {} workflow request(s), {} not started", eventId, eventType, clientId,
            started.size(), workflowsStarted.size(), notStarted.size());
        return CustomerAnswer.of(202, answer, null);
    }

    private void startPipeline(EventRouteStore.Route route, long tenantId, long eventId, String eventType, JsonNode data, String clientId,
        List<Map<String, Object>> started, List<Map<String, Object>> notStarted) {
        Optional<PipelineCatalogue.Entry> pipeline = this.catalogue.find(tenantId, String.valueOf(route.jobId));
        if (!pipeline.isPresent()) {
            notStarted.add(notStarted(route, "The route's pipeline no longer exists."));
            return;
        }
        RunIntake.Start start = new RunIntake.Start();
        start.record = data;
        start.clientId = clientId;
        start.eventId = eventId;
        start.eventType = eventType;
        start.origin = "event " + eventType;
        try {
            RunIntake.Outcome outcome = this.intake.start(pipeline.get(), start);
            if (outcome.refusal != null) {
                notStarted.add(notStarted(route, outcome.refusal.getDetail()));
            } else {
                started.add(outcome.run);
            }
        } catch (RuntimeException failed) {
            logger.error("Event {} could not start pipeline {}", eventId, route.jobId, failed);
            notStarted.add(notStarted(route, "The run could not be queued."));
        }
    }

    private void startWorkflow(EventRouteStore.Route route, long tenantId, long eventId, String eventType, JsonNode data,
        List<Map<String, Object>> workflowsStarted, List<Map<String, Object>> notStarted) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> subject = JSON.convertValue(data, Map.class);
            FormWorkflows.Started request = this.workflows.startFor(tenantId, route.workflowKey, "api-event:" + eventId,
                "api-event-" + eventId + "-route-" + route.routeId, "Event " + eventType, subject);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", String.valueOf(request.instanceId));
            one.put("workflowKey", route.workflowKey);
            one.put("state", request.state);
            workflowsStarted.add(one);
        } catch (RuntimeException failed) {
            logger.warn("Event {} could not start workflow {}: {}", eventId, route.workflowKey, failed.getMessage());
            notStarted.add(notStarted(route, "The workflow request could not be started."));
        }
    }

    private static Map<String, Object> notStarted(EventRouteStore.Route route, String reason) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("kind", EventRoutes.PIPELINE.equals(route.targetKind) ? "pipeline" : "workflow");
        one.put("target", EventRoutes.PIPELINE.equals(route.targetKind) ? String.valueOf(route.jobId) : route.workflowKey);
        one.put("reason", reason);
        return one;
    }

    private static String summary(List<Map<String, Object>> runs, List<Map<String, Object>> workflows, List<Map<String, Object>> refused) {
        Map<String, Object> summary = new LinkedHashMap<>();
        List<Object> runIds = new ArrayList<>();
        runs.forEach(run -> runIds.add(run.get("id")));
        List<Object> workflowIds = new ArrayList<>();
        workflows.forEach(workflow -> workflowIds.add(workflow.get("id")));
        summary.put("runs", runIds);
        summary.put("workflows", workflowIds);
        summary.put("notStarted", refused);
        try {
            return JSON.writeValueAsString(summary);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
