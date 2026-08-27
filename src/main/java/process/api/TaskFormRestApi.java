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
 * Form definitions for task payloads.
 *
 * Reading one is open to anyone who can configure a task, since the task screen needs it to
 * render. Defining one is an admin act: a form decides how everybody else configures that
 * pipeline, and a wrong tag name there produces tasks that fail at run time.
 *
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/taskForm.json")
@PreAuthorize("hasRole('TENANT_USER')")
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
