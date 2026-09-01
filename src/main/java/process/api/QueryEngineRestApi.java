package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.DatabaseConnectionProfileDto;
import process.model.dto.QueryDefinitionDto;
import process.model.dto.QueryExecutionRequestDto;
import process.model.dto.QueryScheduleDto;
import process.model.dto.ResponseDto;
import process.model.service.ConnectionProfileService;
import process.model.service.QueryDefinitionService;
import process.model.service.QueryExecutionService;
import process.model.service.QueryScheduleService;
import process.util.ProcessUtil;

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/queryEngine.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class QueryEngineRestApi {

    private Logger logger = LoggerFactory.getLogger(QueryEngineRestApi.class);

    private final ConnectionProfileService connectionProfileService;
    private final QueryDefinitionService queryDefinitionService;
    private final QueryExecutionService queryExecutionService;
    private final QueryScheduleService queryScheduleService;

    public QueryEngineRestApi(ConnectionProfileService connectionProfileService,
        QueryDefinitionService queryDefinitionService, QueryExecutionService queryExecutionService,
        QueryScheduleService queryScheduleService) {
        this.connectionProfileService = connectionProfileService;
        this.queryDefinitionService = queryDefinitionService;
        this.queryExecutionService = queryExecutionService;
        this.queryScheduleService = queryScheduleService;
    }

    @RequestMapping(value = "/connections/add", method = RequestMethod.POST)
    public ResponseEntity<?> addConnectionProfile(@RequestBody DatabaseConnectionProfileDto dto) {
        try {
            return new ResponseEntity<>(this.connectionProfileService.addConnectionProfile(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addConnectionProfile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/connections/update", method = RequestMethod.PUT)
    public ResponseEntity<?> updateConnectionProfile(@RequestBody DatabaseConnectionProfileDto dto) {
        try {
            return new ResponseEntity<>(this.connectionProfileService.updateConnectionProfile(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateConnectionProfile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/connections/delete", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteConnectionProfile(@RequestParam Long databaseConnectionProfileId) {
        try {
            return new ResponseEntity<>(this.connectionProfileService.deleteConnectionProfile(databaseConnectionProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteConnectionProfile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/connections/fetchAll", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllConnectionProfiles() {
        try {
            return new ResponseEntity<>(this.connectionProfileService.fetchAllConnectionProfiles(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllConnectionProfiles ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/connections/fetchById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchConnectionProfileById(@RequestParam Long databaseConnectionProfileId) {
        try {
            return new ResponseEntity<>(this.connectionProfileService.fetchConnectionProfileById(databaseConnectionProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchConnectionProfileById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // A tenant user may test a connection profile it can already see, but only as it stands:
    // testing an unsaved profile, or a saved one against a different host or password, is held to
    // TENANT_ADMIN inside ConnectionProfileService.
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/connections/testConnection", method = RequestMethod.POST)
    public ResponseEntity<?> testConnection(@RequestBody DatabaseConnectionProfileDto dto) {
        try {
            return new ResponseEntity<>(this.connectionProfileService.testConnection(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while testConnection ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/queries/add", method = RequestMethod.POST)
    public ResponseEntity<?> addQuery(@RequestBody QueryDefinitionDto dto) {
        try {
            return new ResponseEntity<>(this.queryDefinitionService.addQuery(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addQuery ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/queries/update", method = RequestMethod.PUT)
    public ResponseEntity<?> updateQuery(@RequestBody QueryDefinitionDto dto) {
        try {
            return new ResponseEntity<>(this.queryDefinitionService.updateQuery(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateQuery ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/queries/delete", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteQuery(@RequestParam Long queryId) {
        try {
            return new ResponseEntity<>(this.queryDefinitionService.deleteQuery(queryId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteQuery ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/queries/fetchAll", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllQueries() {
        try {
            return new ResponseEntity<>(this.queryDefinitionService.fetchAllQueries(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllQueries ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/queries/fetchById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchQueryById(@RequestParam Long queryId) {
        try {
            return new ResponseEntity<>(this.queryDefinitionService.fetchQueryById(queryId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchQueryById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // The lowered role covers validating a saved query by id. SQL supplied on the request is
    // authoring, so QueryDefinitionService holds that branch to TENANT_ADMIN.
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/queries/validate", method = RequestMethod.POST)
    public ResponseEntity<?> validateQuery(@RequestBody QueryDefinitionDto dto) {
        try {
            return new ResponseEntity<>(this.queryDefinitionService.validateQuery(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while validateQuery ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // Same split as /queries/validate: a saved query may be previewed by a tenant user, ad-hoc
    // SQL only by a tenant admin.
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/queries/preview", method = RequestMethod.POST)
    public ResponseEntity<?> previewQuery(@RequestBody QueryDefinitionDto dto) {
        try {
            return new ResponseEntity<>(this.queryDefinitionService.previewQuery(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while previewQuery ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/executions/execute", method = RequestMethod.POST)
    public ResponseEntity<?> execute(@RequestBody QueryExecutionRequestDto request) {
        try {
            return new ResponseEntity<>(this.queryExecutionService.execute(request), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while execute ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/executions/fetchAll", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllExecutions() {
        try {
            return new ResponseEntity<>(this.queryExecutionService.fetchAllExecutions(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllExecutions ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/executions/fetchById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchExecutionById(@RequestParam Long executionId) {
        try {
            return new ResponseEntity<>(this.queryExecutionService.fetchExecutionById(executionId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchExecutionById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/executions/fetchByQueryId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchExecutionsByQueryId(@RequestParam Long queryId) {
        try {
            return new ResponseEntity<>(this.queryExecutionService.fetchExecutionsByQueryId(queryId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchExecutionsByQueryId ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/schedules/add", method = RequestMethod.POST)
    public ResponseEntity<?> addSchedule(@RequestBody QueryScheduleDto dto) {
        try {
            return new ResponseEntity<>(this.queryScheduleService.addSchedule(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSchedule ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/schedules/update", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSchedule(@RequestBody QueryScheduleDto dto) {
        try {
            return new ResponseEntity<>(this.queryScheduleService.updateSchedule(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSchedule ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/schedules/delete", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteSchedule(@RequestParam Long scheduleId) {
        try {
            return new ResponseEntity<>(this.queryScheduleService.deleteSchedule(scheduleId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSchedule ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/schedules/fetchAll", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllSchedules() {
        try {
            return new ResponseEntity<>(this.queryScheduleService.fetchAllSchedules(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllSchedules ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/schedules/fetchById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchScheduleById(@RequestParam Long scheduleId) {
        try {
            return new ResponseEntity<>(this.queryScheduleService.fetchScheduleById(scheduleId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchScheduleById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
