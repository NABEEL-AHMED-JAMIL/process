package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.model.pojo.TenantRequest;
import process.model.service.impl.TenantRequestServiceImpl;
import process.util.ProcessUtil;

/**
 * Asking for a workspace, and deciding on the asks.
 *
 * Submitting is open, because whoever is asking has no account yet -- that is the point. Reading
 * and deciding are for a platform administrator: a request carries someone's name and address,
 * and granting one creates a tenant.
 *
 * @author Nabeel Ahmed
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping(value = "/tenantRequest.json")
public class TenantRequestRestApi {

    private final Logger logger = LoggerFactory.getLogger(TenantRequestRestApi.class);

    private final TenantRequestServiceImpl tenantRequestService;

    public TenantRequestRestApi(TenantRequestServiceImpl tenantRequestService) {
        this.tenantRequestService = tenantRequestService;
    }

    /** Open to anyone. The service treats everything in the body as unverified. */
    @RequestMapping(value = "/submit", method = RequestMethod.POST)
    public ResponseEntity<?> submit(@RequestBody TenantRequest tenantRequest) {
        try {
            return new ResponseEntity<>(this.tenantRequestService.submit(tenantRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while submitting a tenant request", ex);
            return internalError();
        }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/listRequests", method = RequestMethod.GET)
    public ResponseEntity<?> listRequests() {
        try {
            return new ResponseEntity<>(this.tenantRequestService.listRequests(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listing tenant requests", ex);
            return internalError();
        }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/approve", method = RequestMethod.POST)
    public ResponseEntity<?> approve(@RequestParam Long tenantRequestId,
        @RequestParam(required = false) String tenantCode) {
        try {
            return new ResponseEntity<>(
                this.tenantRequestService.approve(tenantRequestId, tenantCode), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while approving tenant request {}", tenantRequestId, ex);
            return internalError();
        }
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/reject", method = RequestMethod.POST)
    public ResponseEntity<?> reject(@RequestParam Long tenantRequestId,
        @RequestParam(required = false) String note) {
        try {
            return new ResponseEntity<>(
                this.tenantRequestService.reject(tenantRequestId, note), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while rejecting tenant request {}", tenantRequestId, ex);
            return internalError();
        }
    }

    private ResponseEntity<?> internalError() {
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
