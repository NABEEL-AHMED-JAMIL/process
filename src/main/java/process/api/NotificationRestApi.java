package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.service.NotificationCenterService;
import process.util.ProcessUtil;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/notification.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class NotificationRestApi {

    private Logger logger = LoggerFactory.getLogger(NotificationRestApi.class);

    private final NotificationCenterService notificationCenterService;

    public NotificationRestApi(NotificationCenterService notificationCenterService) {
        this.notificationCenterService = notificationCenterService;
    }

    @RequestMapping(value = "/list", method = RequestMethod.GET)
    public ResponseEntity<?> list(
        @RequestParam(required = false) Boolean unreadOnly,
        @RequestParam(required = false) Long page,
        @RequestParam(required = false) Long limit) {
        try {
            return new ResponseEntity<>(this.notificationCenterService.list(unreadOnly, page, limit), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while list.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/unreadCount", method = RequestMethod.GET)
    public ResponseEntity<?> unreadCount() {
        try {
            return new ResponseEntity<>(this.notificationCenterService.unreadCount(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while unreadCount.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/markRead/{notificationId}", method = RequestMethod.POST)
    public ResponseEntity<?> markRead(@PathVariable Long notificationId) {
        try {
            return new ResponseEntity<>(this.notificationCenterService.markRead(notificationId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while markRead.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/markAllRead", method = RequestMethod.POST)
    public ResponseEntity<?> markAllRead() {
        try {
            return new ResponseEntity<>(this.notificationCenterService.markAllRead(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while markAllRead.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
