package process.api;

import org.barco.platform.security.BuilderAction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import process.customer.EventRouteRequest;
import process.customer.EventRoutes;

/**
 * A workspace's event routes for the customer API's POST /v1/events (MIG-332): which pipeline or workflow each of the
 * organisation's event types starts. A workspace administrator's (Integration › API clients); changing them is building
 * the workspace, so a MANAGED workspace's own administrator reads them and our team changes them (@BuilderAction).
 */
@RestController
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('TENANT_ADMIN')")
@RequestMapping("/eventRoute.json")
public class EventRouteRestApi {

    private final EventRoutes routes;

    public EventRouteRestApi(EventRoutes routes) {
        this.routes = routes;
    }

    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public ResponseEntity<?> list() {
        return new ResponseEntity<>(this.routes.list(), HttpStatus.OK);
    }

    /** The pipelines a route may start. */
    @RequestMapping(value = "/targets", method = RequestMethod.GET)
    public ResponseEntity<?> targets() {
        return new ResponseEntity<>(this.routes.targets(), HttpStatus.OK);
    }

    /** Body {routeId?, eventType, targetKind PIPELINE|WORKFLOW, jobId | workflowKey, contractId | contractName, contractVersion, active}. */
    @BuilderAction
    @RequestMapping(value = "/save", method = RequestMethod.POST)
    public ResponseEntity<?> save(@RequestBody EventRouteRequest request) {
        return new ResponseEntity<>(this.routes.save(request), HttpStatus.OK);
    }

    /** Body {routeId}. */
    @BuilderAction
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public ResponseEntity<?> delete(@RequestBody EventRouteRequest request) {
        return new ResponseEntity<>(this.routes.delete(request == null ? null : request.getRouteId()), HttpStatus.OK);
    }
}
