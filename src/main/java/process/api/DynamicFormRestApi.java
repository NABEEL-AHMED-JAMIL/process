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
 * Api use to perform crud operation on dynamic forms (builder + fill-in submissions). Role
 * policy: building a form (add/update/delete form or field) is tenant configuration, so this
 * whole controller defaults to TENANT_ADMIN+ -- every submission-related method (fill/view/edit
 * a form someone else built) is relaxed back down to TENANT_USER below.
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/dynamicForm.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class DynamicFormRestApi {

    private Logger logger = LoggerFactory.getLogger(DynamicFormRestApi.class);

    private final DynamicFormService dynamicFormService;

    public DynamicFormRestApi(DynamicFormService dynamicFormService) {
        this.dynamicFormService = dynamicFormService;
    }

    /**
     * Api use to add a new dynamic form
     * @param dynamicFormDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/addForm", method = RequestMethod.POST)
    public ResponseEntity<?> addForm(@RequestBody DynamicFormDto dynamicFormDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.addForm(dynamicFormDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addForm ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to update a dynamic form's name/description/status
     * @param dynamicFormDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateForm", method = RequestMethod.PUT)
    public ResponseEntity<?> updateForm(@RequestBody DynamicFormDto dynamicFormDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.updateForm(dynamicFormDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateForm ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to soft delete a dynamic form
     * @param dynamicFormId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteForm", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteForm(@RequestParam Long dynamicFormId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.deleteForm(dynamicFormId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteForm ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch all the non-deleted dynamic forms
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchAllForms", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllForms() {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchAllForms(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllForms ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch a single dynamic form with its ordered fields
     * @param dynamicFormId
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchFormByFormId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchFormByFormId(@RequestParam Long dynamicFormId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchFormByFormId(dynamicFormId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchFormByFormId ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch a single dynamic form (with its ordered fields) by its uuid -- the
     * shareable link surfaced on the form list screen, meant to be copied into Postman, a
     * source task config, etc. Same idea as fetchSubmissionByUuid.
     * @param uuid
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchFormByUuid", method = RequestMethod.GET)
    public ResponseEntity<?> fetchFormByUuid(@RequestParam String uuid) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchFormByUuid(uuid), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchFormByUuid ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to add a new field to a dynamic form
     * @param dynamicFormId
     * @param dynamicFormFieldDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/addField", method = RequestMethod.POST)
    public ResponseEntity<?> addField(@RequestParam Long dynamicFormId, @RequestBody DynamicFormFieldDto dynamicFormFieldDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.addField(dynamicFormId, dynamicFormFieldDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addField ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to update an existing field
     * @param dynamicFormFieldDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateField", method = RequestMethod.PUT)
    public ResponseEntity<?> updateField(@RequestBody DynamicFormFieldDto dynamicFormFieldDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.updateField(dynamicFormFieldDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateField ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to remove a field from its form
     * @param dynamicFormFieldId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteField", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteField(@RequestParam Long dynamicFormFieldId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.deleteField(dynamicFormFieldId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteField ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to save a filled-in copy of a form
     * @param dynamicFormSubmissionDto
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/submitForm", method = RequestMethod.POST)
    public ResponseEntity<?> submitForm(@RequestBody DynamicFormSubmissionDto dynamicFormSubmissionDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.submitForm(dynamicFormSubmissionDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while submitForm ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to overwrite an existing submission's payload
     * @param dynamicFormSubmissionDto
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/updateSubmission", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSubmission(@RequestBody DynamicFormSubmissionDto dynamicFormSubmissionDto) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.updateSubmission(dynamicFormSubmissionDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSubmission ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to permanently remove a submission
     * @param dynamicFormSubmissionId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteSubmission", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteSubmission(@RequestParam Long dynamicFormSubmissionId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.deleteSubmission(dynamicFormSubmissionId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSubmission ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch all submissions for a form, newest first
     * @param dynamicFormId
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchSubmissionsByFormId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSubmissionsByFormId(@RequestParam Long dynamicFormId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchSubmissionsByFormId(dynamicFormId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSubmissionsByFormId ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch a single submission -- used when the fill screen is opened directly in edit mode
     * @param dynamicFormSubmissionId
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchSubmissionBySubmissionId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSubmissionBySubmissionId(@RequestParam Long dynamicFormSubmissionId) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchSubmissionBySubmissionId(dynamicFormSubmissionId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSubmissionBySubmissionId ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch a single submission by its uuid -- the shareable link surfaced on the
     * submission detail screen, meant to be copied into Postman, a source task config, etc.
     * @param uuid
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchSubmissionByUuid", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSubmissionByUuid(@RequestParam String uuid) {
        try {
            return new ResponseEntity<>(this.dynamicFormService.fetchSubmissionByUuid(uuid), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSubmissionByUuid ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}
