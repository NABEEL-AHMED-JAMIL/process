package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.pojo.Pipeline;
import process.model.service.impl.PipelineServiceImpl;
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
@RequestMapping(value = "/pipeline.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class PipelineRestApi {

    private final Logger logger = LoggerFactory.getLogger(PipelineRestApi.class);

    private final PipelineServiceImpl pipelineService;

    public PipelineRestApi(PipelineServiceImpl pipelineService) {
        this.pipelineService = pipelineService;
    }

    @RequestMapping(value = {"/list", "/listForms"}, method = RequestMethod.GET)
    public ResponseEntity<?> listForms(
        @RequestParam(required = false) Long page, @RequestParam(required = false) Long limit,
        @RequestParam(required = false) String q, @RequestParam(required = false) String topic,
        @RequestParam(required = false) String status, @RequestParam(required = false) Long tenantId,
        @RequestParam(required = false, defaultValue = "false") boolean onlyMine) {
        try {
            return new ResponseEntity<>(this.pipelineService.listForms(page, limit, q, topic, status, tenantId, onlyMine), HttpStatus.OK);
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
    public ResponseEntity<?> listPipelines(
        @RequestParam(required = false) Long page, @RequestParam(required = false) Long limit,
        @RequestParam(required = false) String q, @RequestParam(required = false) String topic,
        @RequestParam(required = false) String status) {
        try {
            return new ResponseEntity<>(this.pipelineService.listForms(page, limit, q, topic, status, null, false), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listing pipelines", ex);
            return internalError();
        }
    }

    /** The fields of one pipeline, fetched when a list row is opened rather than sent with every row. */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fields", method = RequestMethod.GET)
    public ResponseEntity<?> fields(@RequestParam Long pipelineKey) {
        try {
            return new ResponseEntity<>(this.pipelineService.fieldsFor(pipelineKey), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listing fields", ex);
            return internalError();
        }
    }

    /** The pipelines on one topic -- what a task may pick once its topic is chosen. */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/listForTopic", method = RequestMethod.GET)
    public ResponseEntity<?> listForTopic(@RequestParam Long sourceTaskTypeId) {
        try {
            return new ResponseEntity<>(this.pipelineService.listForTopic(sourceTaskTypeId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listing pipelines for topic {}", sourceTaskTypeId, ex);
            return internalError();
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = {"/definition", "/formForPipeline"}, method = RequestMethod.GET)
    public ResponseEntity<?> formForPipeline(@RequestParam String pipelineId,
        @RequestParam(required = false) Long tenantId) {
        try {
            return new ResponseEntity<>(this.pipelineService.formForPipeline(pipelineId, tenantId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while reading the form for pipeline {}", pipelineId, ex);
            return internalError();
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = {"/save", "/saveForm"}, method = RequestMethod.POST)
    public ResponseEntity<?> saveForm(@RequestBody Pipeline form) {
        try {
            return new ResponseEntity<>(this.pipelineService.saveForm(form), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while saving a task form", ex);
            return internalError();
        }
    }

    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = {"/delete", "/deleteForm"}, method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteForm(@RequestParam Long pipelineKey) {
        try {
            return new ResponseEntity<>(this.pipelineService.deleteForm(pipelineKey), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleting task form {}", pipelineKey, ex);
            return internalError();
        }
    }

    private ResponseEntity<?> internalError() {
        return new ResponseEntity<>(
            new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
