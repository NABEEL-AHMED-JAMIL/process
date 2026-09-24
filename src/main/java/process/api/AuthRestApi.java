package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.model.service.AuthService;
import process.util.ProcessUtil;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/auth.json")
public class AuthRestApi {

    private Logger logger = LoggerFactory.getLogger(AuthRestApi.class);

    private final AuthService authService;

    public AuthRestApi(AuthService authService) {
        this.authService = authService;
    }

    @RequestMapping(value = "/login", method = RequestMethod.POST)
    public ResponseEntity<?> login(@RequestBody LoginRequestDto loginRequestDto) {
        try {
            return new ResponseEntity<>(this.authService.login(loginRequestDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while login ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Signs out: the bearer token and the refresh token in the body, if any, stop working on every
     * instance (MIG-14). Open like the rest of /auth.json -- the tokens are the proof, and a token that
     * proves nothing is simply ignored.
     */
    @RequestMapping(value = "/logout", method = RequestMethod.POST)
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestBody(required = false) Map<String, String> body) {
        try {
            String accessToken = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
            return new ResponseEntity<>(this.authService.logout(accessToken, body == null ? null : body.get("refreshToken")),
                HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while logout ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/refresh", method = RequestMethod.POST)
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        try {
            return new ResponseEntity<>(this.authService.refresh(body.get("refreshToken")), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while refresh ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
