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
 * Api use to log in (username/password -> access+refresh JWT) and refresh an access token.
 * Both endpoints are public (see SecurityConfig's permitAll list) -- everything else requires
 * a valid "Authorization: Bearer <accessToken>" header.
 * @author Nabeel Ahmed
 */
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
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/refresh", method = RequestMethod.POST)
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        try {
            return new ResponseEntity<>(this.authService.refresh(body.get("refreshToken")), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while refresh ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}
