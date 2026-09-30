package process.api;

import org.barco.platform.security.BuilderAction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import process.model.dto.ResponseDto;
import process.settings.PipelineConfigDto;
import process.settings.PipelineConfigService;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

/**
 * Configuration values and secrets, per workspace (MIG-167). A secret goes in on POST or PUT and never comes out.
 * A refusal is a 200 with status ERROR and a sentence, as everywhere on /setting.json.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/setting.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class PipelineConfigRestApi {

    private final PipelineConfigService service;

    public PipelineConfigRestApi(PipelineConfigService service) {
        this.service = service;
    }

    @GetMapping(value = "/pipelineConfig")
    public ResponseEntity<ResponseDto> list(@RequestParam(required = false) Long tenantId) {
        return new ResponseEntity<>(this.service.list(tenantId), HttpStatus.OK);
    }

    @BuilderAction
    @PostMapping(value = "/pipelineConfig")
    public ResponseEntity<ResponseDto> add(@RequestBody PipelineConfigDto request) {
        return new ResponseEntity<>(this.service.add(request), HttpStatus.OK);
    }

    @BuilderAction
    @PutMapping(value = "/pipelineConfig")
    public ResponseEntity<ResponseDto> update(@RequestBody PipelineConfigDto request) {
        return new ResponseEntity<>(this.service.update(request), HttpStatus.OK);
    }

    @BuilderAction
    @DeleteMapping(value = "/pipelineConfig")
    public ResponseEntity<ResponseDto> delete(@RequestParam Long id) {
        return new ResponseEntity<>(this.service.delete(id), HttpStatus.OK);
    }
}
