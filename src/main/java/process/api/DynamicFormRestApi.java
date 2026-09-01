package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.DynamicFormDto;
import process.model.dto.DynamicFormFieldDto;
import process.model.dto.DynamicFormSubmissionDto;
import process.model.dto.ResponseDto;
import process.model.service.DynamicFormService;
import process.util.ProcessUtil;

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/dynamicForm.json")
// Authoring a form and reading what people answered are both administrative: the submissions
// hold whatever the form asked for, which on a shared form is routinely personal. Only the two
// submit endpoints and the two public uuid lookups sit below this, and each says so in its own
// right -- an override here is never inherited, because @PreAuthorize is not repeatable and a
// method-level one replaces the class-level one outright.
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class DynamicFormRestApi {

    private Logger logger = LoggerFactory.getLogger(DynamicFormRestApi.class);

    private final DynamicFormService dynamicFormService;

    public DynamicFormRestApi(DynamicFormService dynamicFormService) {
        this.dynamicFormService = dynamicFormService;
    }

    @RequestMapping(value = "/addForm", method = RequestMethod.POST)
    public ResponseEntity<?> addForm(@RequestBody DynamicFormDto dynamicFormDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.addForm(dynamicFormDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addForm ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/updateForm", method = RequestMethod.PUT)
    public ResponseEntity<?> updateForm(@RequestBody DynamicFormDto dynamicFormDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.updateForm(dynamicFormDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateForm ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteForm", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteForm(@RequestParam Long dynamicFormId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.deleteForm(dynamicFormId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteForm ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchAllForms", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllForms() {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchAllForms(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllForms ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchFormByFormId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchFormByFormId(@RequestParam Long dynamicFormId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchFormByFormId(dynamicFormId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchFormByFormId ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("permitAll()")
    @RequestMapping(value = "/fetchFormByUuid", method = RequestMethod.GET)
    public ResponseEntity<?> fetchFormByUuid(@RequestParam String uuid) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchFormByUuid(uuid), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchFormByUuid ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/addField", method = RequestMethod.POST)
    public ResponseEntity<?> addField(@RequestParam Long dynamicFormId, @RequestBody DynamicFormFieldDto dynamicFormFieldDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.addField(dynamicFormId, dynamicFormFieldDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addField ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/updateField", method = RequestMethod.PUT)
    public ResponseEntity<?> updateField(@RequestBody DynamicFormFieldDto dynamicFormFieldDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.updateField(dynamicFormFieldDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateField ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteField", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteField(@RequestParam Long dynamicFormFieldId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.deleteField(dynamicFormFieldId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteField ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Answering a form, unlike reading one, still needs an account.
     *
     * fetchFormByUuid above is permitAll so a share link opens for anyone, and the console
     * already tells a visitor with only the link that they cannot answer. Opening this to match
     * is a bigger change than an annotation: submitForm reads the tenant from TenantContext,
     * which an anonymous caller has none of, and an unauthenticated write endpoint needs a rate
     * limit and the permitAll matcher in SecurityConfig. Until all three are in place this stays
     * closed rather than half open.
     */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/submitForm", method = RequestMethod.POST)
    public ResponseEntity<?> submitForm(@RequestBody DynamicFormSubmissionDto dynamicFormSubmissionDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.submitForm(dynamicFormSubmissionDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while submitForm ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/updateSubmission", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSubmission(@RequestBody DynamicFormSubmissionDto dynamicFormSubmissionDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.updateSubmission(dynamicFormSubmissionDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSubmission ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteSubmission", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteSubmission(@RequestParam Long dynamicFormSubmissionId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.deleteSubmission(dynamicFormSubmissionId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSubmission ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchSubmissionsByFormId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSubmissionsByFormId(@RequestParam Long dynamicFormId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchSubmissionsByFormId(dynamicFormId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSubmissionsByFormId ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchSubmissionBySubmissionId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSubmissionBySubmissionId(@RequestParam Long dynamicFormSubmissionId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchSubmissionBySubmissionId(dynamicFormSubmissionId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSubmissionBySubmissionId ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("permitAll()")
    @RequestMapping(value = "/fetchSubmissionByUuid", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSubmissionByUuid(@RequestParam String uuid) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchSubmissionByUuid(uuid), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSubmissionByUuid ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
