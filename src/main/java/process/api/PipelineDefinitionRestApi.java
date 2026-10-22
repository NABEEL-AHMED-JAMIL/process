package process.api;

import org.barco.platform.security.BuilderAction;
import org.barco.platform.security.NoAudit;
import org.barco.platform.security.AuditedRead;
import org.barco.platform.security.PlatformLevel;
import org.barco.platform.security.WorkspaceData;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.pipeline.PipelineDefinitionService;
import process.pipeline.StepPreview;

/**
 * A pipeline's definition as ordered steps (MIG-230), for the console's step builder (MIG-249), under the prefix the
 * gateway already sends to Core and gates as the pipelines page -- so no gateway route or page key is new:
 *
 * <ul>
 *   <li>GET pipeline.json/steps/definition: the pipeline's definition -- its latest version, or the legacy step every
 *       pipeline without one runs as -- as the object, JSON and YAML, with its versions;</li>
 *   <li>POST pipeline.json/steps/validate: a draft's problems, each at its path, and its JSON and YAML;</li>
 *   <li>POST pipeline.json/steps/save: the draft as the next version (tenant admins, as the pipeline's form);</li>
 *   <li>GET pipeline.json/steps/tasks: the Task Registry as the caller's workspace sees it (MIG-231) -- each task's
 *       entry with its config schema and enabled, and one Legacy entry per pipeline of the workspace;</li>
 *   <li>POST pipeline.json/steps/preview (AI tools batch): one step on at most 50 rows, sandboxed, as the person (StepPreview).</li>
 *   <li>POST pipeline.json/steps/tasks/enabled: a workspace admin switches a task on, off, or back to its default (not a
 *       task a platform administrator blocked for the workspace, owner #16). The platform's controls, the audit and the
 *       usage counts are {@link TaskRegistryRestApi}'s.</li>
 *   <li>GET pipeline.json/steps/reads (UX review 2026-10-13, I1): what the workspace's pipelines read -- storage, API
 *       requests, database connections, document types -- grouped by place, with the pipelines and steps that read each.
 *       Gated as Connections › Sources (page key sources: Core's and Identity's PageKey), not as the pipelines page.</li>
 * </ul>
 *
 * A missing pipelineKey is Spring's 400; a pipeline of another workspace, or none, is the envelope's
 * "That pipeline no longer exists." -- the pipeline form's own words.
 */
// ZT-A12 follow-up (owner 2026-10-21, "Managed session only"): a workspace's step definitions, their validation,
// previews on its rows and task switches are "Work in this workspace"'s only; the shared step catalogue stays platform-level.
@WorkspaceData
@RestController
@PreAuthorize("hasRole('TENANT_USER')")
public class PipelineDefinitionRestApi {

    private final PipelineDefinitionService service;

    private final StepPreview preview;

    public PipelineDefinitionRestApi(PipelineDefinitionService service, StepPreview preview) {
        this.service = service;
        this.preview = preview;
    }

    @RequestMapping(value = "/pipeline.json/steps/definition", method = RequestMethod.GET)
    public ResponseEntity<?> definition(@RequestParam Long pipelineKey) {
        return new ResponseEntity<>(this.service.read(pipelineKey), HttpStatus.OK);
    }

    @NoAudit(reason = "validates the definition the request sent; reads no data and saves nothing")
    @RequestMapping(value = "/pipeline.json/steps/validate", method = RequestMethod.POST)
    public ResponseEntity<?> validate(@RequestBody PipelineDefinitionService.DefinitionRequest request) {
        return new ResponseEntity<>(this.service.validate(request), HttpStatus.OK);
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @BuilderAction
    @RequestMapping(value = "/pipeline.json/steps/save", method = RequestMethod.POST)
    public ResponseEntity<?> save(@RequestBody PipelineDefinitionService.DefinitionRequest request) {
        return new ResponseEntity<>(this.service.save(request), HttpStatus.OK);
    }

    /**
     * AI tools batch (owner item #14): one step run on at most 50 rows, outside any run, as the person -- sandboxed (StepPreview):
     * rows-to-rows tasks only, nothing written or sent, reads only, capped; the output's columns and at most 20 masked rows.
     * Not a builder action: it changes nothing.
     */
    @AuditedRead(value = "step preview", bodyId = {"task"})
    @RequestMapping(value = "/pipeline.json/steps/preview", method = RequestMethod.POST)
    public ResponseEntity<?> preview(@RequestBody StepPreview.PreviewRequest request) {
        return new ResponseEntity<>(this.preview.preview(request), HttpStatus.OK);
    }

    /** Owner #16: a platform administrator names the workspace (the shared selector's tenantId); anyone else reads their own. */
    // ZT-A12 follow-up (owner 2026-10-21, "Managed session only"): the step catalogue every workspace shares (codes, names,
    // what each takes) and whether a task is switched on -- no pipeline, no row -- stays platform-level.
    @PlatformLevel
    @RequestMapping(value = "/pipeline.json/steps/tasks", method = RequestMethod.GET)
    public ResponseEntity<?> tasks(@RequestParam(required = false) Long tenantId) {
        return new ResponseEntity<>(this.service.tasks(tenantId), HttpStatus.OK);
    }

    // ZT-A12 follow-up (owner 2026-10-21, "Managed session only"): the kinds of reads a step may make, the same for every
    // workspace, stay platform-level.
    @PlatformLevel
    @RequestMapping(value = "/pipeline.json/steps/reads", method = RequestMethod.GET)
    public ResponseEntity<?> reads() {
        return new ResponseEntity<>(this.service.reads(), HttpStatus.OK);
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @BuilderAction
    @RequestMapping(value = "/pipeline.json/steps/tasks/enabled", method = RequestMethod.POST)
    public ResponseEntity<?> switchTask(@RequestBody PipelineDefinitionService.TaskSwitchRequest request) {
        return new ResponseEntity<>(this.service.switchTask(request), HttpStatus.OK);
    }
}
