package process.api;

import org.barco.platform.security.BuilderAction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import process.model.dto.ResponseDto;
import process.settings.TaskReferenceDto;
import process.settings.TaskReferenceService;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;

/** Home pages and task groups, per workspace (MIG-167): what the task editor's two dropdowns pick from. */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/setting.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class TaskReferenceRestApi {

    private final TaskReferenceService service;

    public TaskReferenceRestApi(TaskReferenceService service) {
        this.service = service;
    }

    @GetMapping(value = "/taskReferences")
    public ResponseEntity<ResponseDto> list(@RequestParam String kind, @RequestParam(required = false) Long tenantId) {
        return new ResponseEntity<>(this.service.list(kind, tenantId), HttpStatus.OK);
    }

    @BuilderAction
    @PostMapping(value = "/taskReferences")
    public ResponseEntity<ResponseDto> add(@RequestBody TaskReferenceDto request) {
        return new ResponseEntity<>(this.service.add(request), HttpStatus.OK);
    }

    @BuilderAction
    @PutMapping(value = "/taskReferences")
    public ResponseEntity<ResponseDto> update(@RequestBody TaskReferenceDto request) {
        return new ResponseEntity<>(this.service.update(request), HttpStatus.OK);
    }

    @BuilderAction
    @DeleteMapping(value = "/taskReferences")
    public ResponseEntity<ResponseDto> delete(@RequestParam Long id) {
        return new ResponseEntity<>(this.service.delete(id), HttpStatus.OK);
    }
}
