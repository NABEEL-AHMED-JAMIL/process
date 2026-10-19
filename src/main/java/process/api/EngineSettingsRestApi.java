package process.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import process.model.dto.ResponseDto;
import process.settings.EngineSettingsService;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Core's engine settings (MIG-167; owner #24, 2026-10-19), platform admin only: the allow-listed dials (EngineDial) with
 * their ranges, defaults and the workspaces that have their own value; a change, a reset to the default, and the history
 * of both. The watermarks are read-only. Nothing else can be written here.
 */
@RestController
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

    /** Body {key, value, tenantId?}: the platform's value, or one workspace's own when tenantId is set. */
    @PutMapping(value = "/engineSettings")
    public ResponseEntity<ResponseDto> update(@RequestBody Map<String, Object> request) {
        return new ResponseEntity<>(this.service.update(text(request, "key"), text(request, "value"), number(request, "tenantId")),
            HttpStatus.OK);
    }

    /** Body {key, tenantId?}: back to the default, or a workspace back to the platform's value. */
    @PostMapping(value = "/engineSettings/reset")
    public ResponseEntity<ResponseDto> reset(@RequestBody Map<String, Object> request) {
        return new ResponseEntity<>(this.service.reset(text(request, "key"), number(request, "tenantId")), HttpStatus.OK);
    }

    @GetMapping(value = "/engineSettings/history")
    public ResponseEntity<ResponseDto> history(@RequestParam(value = "key", required = false) String key,
        @RequestParam(value = "limit", required = false) Integer limit) {
        return new ResponseEntity<>(this.service.history(key, limit), HttpStatus.OK);
    }

    private static String text(Map<String, Object> request, String name) {
        Object value = request == null ? null : request.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static Long number(Map<String, Object> request, String name) {
        Object value = request == null ? null : request.get(name);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && ((String) value).trim().matches("[0-9]{1,18}")) {
            return Long.valueOf(((String) value).trim());
        }
        return null;
    }
}
