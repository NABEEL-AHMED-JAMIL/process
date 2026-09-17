package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.PageAccessProfileDto;
import process.model.dto.ResponseDto;
import process.model.service.PageAccessService;
import process.util.ProcessUtil;

/**
 * Access profiles. Reading the catalogue and your own pages is open to every signed-in
 * person; the profiles themselves are a tenant admin's.
 *
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/pageAccess.json")
public class PageAccessRestApi {

    private final Logger logger = LoggerFactory.getLogger(PageAccessRestApi.class);

    private final PageAccessService pageAccessService;

    public PageAccessRestApi(PageAccessService pageAccessService) {
        this.pageAccessService = pageAccessService;
    }

    @RequestMapping(value = "/pages", method = RequestMethod.GET)
    public ResponseEntity<?> pages() {
        return new ResponseEntity<>(this.pageAccessService.catalogue(), HttpStatus.OK);
    }

    @RequestMapping(value = "/mine", method = RequestMethod.GET)
    public ResponseEntity<?> mine() {
        try {
            return new ResponseEntity<>(this.pageAccessService.mine(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while mine.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/requestAccess", method = RequestMethod.POST)
    public ResponseEntity<?> requestAccess(@RequestParam String pageKey) {
        try {
            return new ResponseEntity<>(this.pageAccessService.requestAccess(pageKey), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while requestAccess.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/listProfiles", method = RequestMethod.GET)
    public ResponseEntity<?> listProfiles(@RequestParam(required = false) Long tenantId) {
        try {
            return new ResponseEntity<>(this.pageAccessService.listProfiles(tenantId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listProfiles.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/addProfile", method = RequestMethod.POST)
    public ResponseEntity<?> addProfile(@RequestBody PageAccessProfileDto dto) {
        try {
            return new ResponseEntity<>(this.pageAccessService.addProfile(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addProfile.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/updateProfile", method = RequestMethod.PUT)
    public ResponseEntity<?> updateProfile(@RequestBody PageAccessProfileDto dto) {
        try {
            return new ResponseEntity<>(this.pageAccessService.updateProfile(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateProfile.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/deleteProfile", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteProfile(@RequestParam Long pageAccessProfileId) {
        try {
            return new ResponseEntity<>(this.pageAccessService.deleteProfile(pageAccessProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteProfile.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/listPeople", method = RequestMethod.GET)
    public ResponseEntity<?> listPeople(@RequestParam(required = false) Long tenantId) {
        try {
            return new ResponseEntity<>(this.pageAccessService.listPeople(tenantId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listPeople.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/assignProfile", method = RequestMethod.PUT)
    public ResponseEntity<?> assignProfile(@RequestParam Long appUserId,
        @RequestParam(required = false) Long pageAccessProfileId) {
        try {
            return new ResponseEntity<>(this.pageAccessService.assignProfile(appUserId, pageAccessProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while assignProfile.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/setPageAccess", method = RequestMethod.PUT)
    public ResponseEntity<?> setPageAccess(@RequestParam Long appUserId, @RequestParam String pageKey,
        @RequestParam boolean allowed) {
        try {
            return new ResponseEntity<>(this.pageAccessService.setPageAccess(appUserId, pageKey, allowed), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while setPageAccess.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/clearPageAccess", method = RequestMethod.DELETE)
    public ResponseEntity<?> clearPageAccess(@RequestParam Long appUserId) {
        try {
            return new ResponseEntity<>(this.pageAccessService.clearPageAccess(appUserId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while clearPageAccess.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/setDefaultProfile", method = RequestMethod.PUT)
    public ResponseEntity<?> setDefaultProfile(@RequestParam Long pageAccessProfileId) {
        try {
            return new ResponseEntity<>(this.pageAccessService.setDefaultProfile(pageAccessProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while setDefaultProfile.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
