package process.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.settings.PipelineConfigDto;
import process.settings.PipelineConfigService;

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

    @PostMapping(value = "/pipelineConfig")
    public ResponseEntity<ResponseDto> add(@RequestBody PipelineConfigDto request) {
        return new ResponseEntity<>(this.service.add(request), HttpStatus.OK);
    }

    @PutMapping(value = "/pipelineConfig")
    public ResponseEntity<ResponseDto> update(@RequestBody PipelineConfigDto request) {
        return new ResponseEntity<>(this.service.update(request), HttpStatus.OK);
    }

    @DeleteMapping(value = "/pipelineConfig")
    public ResponseEntity<ResponseDto> delete(@RequestParam Long id) {
        return new ResponseEntity<>(this.service.delete(id), HttpStatus.OK);
    }
}
