package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.dto.TenantDto;
import process.model.service.TenantService;
import process.util.ProcessUtil;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/tenant.json")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class TenantRestApi {

    private Logger logger = LoggerFactory.getLogger(TenantRestApi.class);

    private final TenantService tenantService;

    public TenantRestApi(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @RequestMapping(value = "/listTenants", method = RequestMethod.GET)
    public ResponseEntity<?> listTenants() {
        try {
            return new ResponseEntity<>(this.tenantService.listTenants(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listTenants ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/addTenant", method = RequestMethod.POST)
    public ResponseEntity<?> addTenant(@RequestBody TenantDto tenantDto) {
        try {
            return new ResponseEntity<>(this.tenantService.addTenant(tenantDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addTenant ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/updateTenant", method = RequestMethod.PUT)
    public ResponseEntity<?> updateTenant(@RequestBody TenantDto tenantDto) {
        try {
            return new ResponseEntity<>(this.tenantService.updateTenant(tenantDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateTenant ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/changeTenantStatus", method = RequestMethod.PUT)
    public ResponseEntity<?> changeTenantStatus(@RequestBody TenantDto tenantDto) {
        try {
            return new ResponseEntity<>(this.tenantService.changeTenantStatus(tenantDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while changeTenantStatus ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}
