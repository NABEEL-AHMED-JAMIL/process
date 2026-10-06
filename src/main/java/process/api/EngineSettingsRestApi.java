package process.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import process.model.dto.ResponseDto;
import process.settings.EngineSettingsService;

import java.util.Map;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Core's engine settings (MIG-167), platform admin only: QUEUE_FETCH_LIMIT is editable, the watermarks are read-only.
 * No delete and no rename exist: the engine reads each setting by name.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/setting.json")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class EngineSettingsRestApi {

    private final EngineSettingsService service;

    public EngineSettingsRestApi(EngineSettingsService service) {
        this.service = service;
    }

    @GetMapping(value = "/engineSettings")
    public ResponseEntity<ResponseDto> list() {
        return new ResponseEntity<>(this.service.list(), HttpStatus.OK);
    }

    /** Body {key, value}. */
    @PutMapping(value = "/engineSettings")
    public ResponseEntity<ResponseDto> update(@RequestBody Map<String, String> request) {
        return new ResponseEntity<>(this.service.update(request == null ? null : request.get("key"),
            request == null ? null : request.get("value")), HttpStatus.OK);
    }
}
