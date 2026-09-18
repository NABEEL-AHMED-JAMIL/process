package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.AiModelConnectionDto;
import process.model.dto.ResponseDto;
import process.model.service.impl.AiModelConnectionServiceImpl;
import process.util.ProcessUtil;

/** Model connections -- an admin's screen throughout; a prompt names one, a user never sees the key. */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/aiConnection.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class AiModelConnectionRestApi {

    private final Logger logger = LoggerFactory.getLogger(AiModelConnectionRestApi.class);
    private final AiModelConnectionServiceImpl service;

    public AiModelConnectionRestApi(AiModelConnectionServiceImpl service) { this.service = service; }

    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public ResponseEntity<?> list() {
        try { return new ResponseEntity<>(this.service.list(), HttpStatus.OK); }
        catch (Exception ex) { return this.failed("list", ex); }
    }

    @RequestMapping(value = "/save", method = RequestMethod.POST)
    public ResponseEntity<?> save(@RequestBody AiModelConnectionDto dto) {
        try { return new ResponseEntity<>(this.service.save(dto), HttpStatus.OK); }
        catch (Exception ex) { return this.failed("save", ex); }
    }

    @RequestMapping(value = "/setDefault", method = RequestMethod.PUT)
    public ResponseEntity<?> setDefault(@RequestParam Long connectionId) {
        try { return new ResponseEntity<>(this.service.setDefault(connectionId), HttpStatus.OK); }
        catch (Exception ex) { return this.failed("setDefault", ex); }
    }

    @RequestMapping(value = "/test", method = RequestMethod.POST)
    public ResponseEntity<?> test(@RequestParam Long connectionId) {
        try { return new ResponseEntity<>(this.service.test(connectionId), HttpStatus.OK); }
        catch (Exception ex) { return this.failed("test", ex); }
    }

    @RequestMapping(value = "/delete", method = RequestMethod.DELETE)
    public ResponseEntity<?> delete(@RequestParam Long connectionId) {
        try { return new ResponseEntity<>(this.service.delete(connectionId), HttpStatus.OK); }
        catch (Exception ex) { return this.failed("delete", ex); }
    }

    private ResponseEntity<?> failed(String what, Exception ex) {
        this.logger.error("An error occurred while {} model connection", what, ex);
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
