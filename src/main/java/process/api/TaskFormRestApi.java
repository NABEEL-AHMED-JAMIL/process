package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.pojo.TaskForm;
import process.model.service.impl.TaskFormServiceImpl;
import process.util.ProcessUtil;

/**
 * Form definitions for task payloads -- and, since a pipeline no longer exists any other way,
 * the catalogue of which pipelines a tenant has at all.
 *
 * Defining one is an admin act: a form decides how everybody else configures that pipeline, and
 * a wrong tag name there produces tasks that fail at run time. listForms, the admin-facing
 * browse/edit/delete surface, stays tenant-admin only for that reason.
 *
 * Two narrower reads are open to anyone who can configure a task, since Source Task's own screen
 * is: listPipelines, the same rows without the admin framing, for the Pipeline picker; and
 * formForPipeline, the single form for whichever pipeline is already chosen, for rendering it.
 * Hence a tenant-admin default with two methods lowered rather than the other way round.
 *
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/taskForm.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class TaskFormRestApi {

    private final Logger logger = LoggerFactory.getLogger(TaskFormRestApi.class);

    private final TaskFormServiceImpl taskFormService;

    public TaskFormRestApi(TaskFormServiceImpl taskFormService) {
        this.taskFormService = taskFormService;
    }

    @RequestMapping(value = "/listForms", method = RequestMethod.GET)
    public ResponseEntity<?> listForms() {
        try {
            return new ResponseEntity<>(this.taskFormService.listForms(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listing task forms", ex);
            return internalError();
        }
    }

    /**
     * The same list {@link #listForms()} returns, reachable by anyone who can configure a
     * task -- Source Task's Pipeline picker (both frontends) reads this rather than the
     * admin-only listForms, since a plain tenant user can open that screen too. Trust here is
     * no wider than formForPipeline already grants: that endpoint hands a TENANT_USER one
     * pipeline's full field definition already; this hands the same shape for all of the
     * caller's own pipelines at once, not a new class of exposure.
     */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/listPipelines", method = RequestMethod.GET)
    public ResponseEntity<?> listPipelines() {
        try {
            return new ResponseEntity<>(this.taskFormService.listForms(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listing pipelines", ex);
            return internalError();
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/formForPipeline", method = RequestMethod.GET)
    public ResponseEntity<?> formForPipeline(@RequestParam String pipelineId) {
        try {
            return new ResponseEntity<>(this.taskFormService.formForPipeline(pipelineId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while reading the form for pipeline {}", pipelineId, ex);
            return internalError();
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/saveForm", method = RequestMethod.POST)
    public ResponseEntity<?> saveForm(@RequestBody TaskForm form) {
        try {
            return new ResponseEntity<>(this.taskFormService.saveForm(form), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while saving a task form", ex);
            return internalError();
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/deleteForm", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteForm(@RequestParam Long taskFormId) {
        try {
            return new ResponseEntity<>(this.taskFormService.deleteForm(taskFormId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleting task form {}", taskFormId, ex);
            return internalError();
        }
    }

    private ResponseEntity<?> internalError() {
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
