package process.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.pipeline.PipelineDefinitionService;

/**
 * A pipeline's definition as ordered steps (MIG-230), for the console's step builder (MIG-249), under the prefix the
 * gateway already sends to Core and gates as the pipelines page -- so no gateway route or page key is new:
 *
 * <ul>
 *   <li>GET pipeline.json/steps/definition: the pipeline's definition -- its latest version, or the legacy step every
 *       pipeline without one runs as -- as the object, JSON and YAML, with its versions;</li>
 *   <li>POST pipeline.json/steps/validate: a draft's problems, each at its path, and its JSON and YAML;</li>
 *   <li>POST pipeline.json/steps/save: the draft as the next version (tenant admins, as the pipeline's form);</li>
 *   <li>GET pipeline.json/steps/tasks: the tasks a step may run.</li>
 * </ul>
 *
 * A missing pipelineKey is Spring's 400; a pipeline of another workspace, or none, is the envelope's
 * "That pipeline no longer exists." -- the pipeline form's own words.
 */
@RestController
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('TENANT_USER')")
public class PipelineDefinitionRestApi {

    private final PipelineDefinitionService service;

    public PipelineDefinitionRestApi(PipelineDefinitionService service) {
        this.service = service;
    }

    @RequestMapping(value = "/pipeline.json/steps/definition", method = RequestMethod.GET)
    public ResponseEntity<?> definition(@RequestParam Long pipelineKey) {
        return new ResponseEntity<>(this.service.read(pipelineKey), HttpStatus.OK);
    }

    @RequestMapping(value = "/pipeline.json/steps/validate", method = RequestMethod.POST)
    public ResponseEntity<?> validate(@RequestBody PipelineDefinitionService.DefinitionRequest request) {
        return new ResponseEntity<>(this.service.validate(request), HttpStatus.OK);
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/pipeline.json/steps/save", method = RequestMethod.POST)
    public ResponseEntity<?> save(@RequestBody PipelineDefinitionService.DefinitionRequest request) {
        return new ResponseEntity<>(this.service.save(request), HttpStatus.OK);
    }

    @RequestMapping(value = "/pipeline.json/steps/tasks", method = RequestMethod.GET)
    public ResponseEntity<?> tasks() {
        return new ResponseEntity<>(this.service.tasks(), HttpStatus.OK);
    }
}
