package process.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.pipeline.TaskControlService;
import process.pipeline.TaskUsage;

/**
 * The Task Registry's controls and counts (owner #16, #17; 2026-10-19), under the prefix the gateway already sends to Core
 * and gates as the pipelines page, beside GET pipeline.json/steps/tasks:
 *
 * <ul>
 *   <li>POST pipeline.json/steps/tasks/platformState?tenantId= {code, state, reason}: a platform administrator sets a task
 *       to default, enabled, disabled or blocked for one workspace (TaskControlService).</li>
 *   <li>GET pipeline.json/steps/tasks/history?code=&amp;tenantId=: who switched, blocked or reset a task (or every task) in
 *       the workspace, newest first.</li>
 *   <li>GET pipeline.json/steps/tasks/usage?tenantId=: per task and per Legacy row, the pipelines that use it and their
 *       runs this month (TaskUsage).</li>
 *   <li>GET pipeline.json/steps/tasks/pipelines?code=|pipelineKey=&amp;tenantId=: the pipelines behind a count.</li>
 *   <li>GET pipeline.json/steps/tasks/runs?code=|pipelineKey=&amp;limit=&amp;tenantId=: their runs this month, newest first.</li>
 * </ul>
 *
 * A workspace administrator's tenantId is ignored (their own workspace, row security); a platform administrator's names
 * the workspace, or none for every workspace (the counts and lists only).
 */
@RestController
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class TaskRegistryRestApi {

    private final TaskControlService controls;
    private final TaskUsage usage;

    public TaskRegistryRestApi(TaskControlService controls, TaskUsage usage) {
        this.controls = controls;
        this.usage = usage;
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/pipeline.json/steps/tasks/platformState", method = RequestMethod.POST)
    public ResponseEntity<?> platformState(@RequestParam Long tenantId, @RequestBody TaskControlService.PlatformControl body) {
        return new ResponseEntity<>(this.controls.platformState(body, tenantId), HttpStatus.OK);
    }

    @RequestMapping(value = "/pipeline.json/steps/tasks/history", method = RequestMethod.GET)
    public ResponseEntity<?> history(@RequestParam(required = false) String code, @RequestParam(required = false) Long tenantId) {
        return new ResponseEntity<>(this.controls.history(code, tenantId), HttpStatus.OK);
    }

    @RequestMapping(value = "/pipeline.json/steps/tasks/usage", method = RequestMethod.GET)
    public ResponseEntity<?> usage(@RequestParam(required = false) Long tenantId) {
        return new ResponseEntity<>(this.usage.usage(tenantId), HttpStatus.OK);
    }

    @RequestMapping(value = "/pipeline.json/steps/tasks/pipelines", method = RequestMethod.GET)
    public ResponseEntity<?> pipelines(@RequestParam(required = false) String code, @RequestParam(required = false) Long pipelineKey,
        @RequestParam(required = false) Long tenantId) {
        return new ResponseEntity<>(this.usage.pipelines(code, pipelineKey, tenantId), HttpStatus.OK);
    }

    @RequestMapping(value = "/pipeline.json/steps/tasks/runs", method = RequestMethod.GET)
    public ResponseEntity<?> runs(@RequestParam(required = false) String code, @RequestParam(required = false) Long pipelineKey,
        @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long tenantId) {
        return new ResponseEntity<>(this.usage.runs(code, pipelineKey, limit, tenantId), HttpStatus.OK);
    }
}
