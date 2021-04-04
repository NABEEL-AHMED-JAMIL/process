package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ProcessDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.Job;
import process.model.repository.projection.JobViewProjection;
import process.model.service.impl.TransactionServiceImpl;
import process.util.ProcessTimeUtil;
import process.util.exception.ExceptionUtil;
import java.util.*;


@RestController
@CrossOrigin(origins = "http://localhost:4200")
@RequestMapping(value = "/webPortal.json")
public class WebPortalRestApi {

    private Logger logger = LoggerFactory.getLogger(WebPortalRestApi.class);

    @Autowired
    private TransactionServiceImpl transactionService;

    /**
     * This method use to fetch the job-setting for validation process
     * @return ResponseEntity<?> getProcessSetting
     */
    @RequestMapping(value = "/getProcessSetting", method = RequestMethod.GET)
    public ResponseEntity<?> getProcessSetting() {
        logger.info("##### getProcessSetting Start");
        try {
            Map<String, Object> processSetting = new HashMap<>();
            processSetting.put("frequency", ProcessTimeUtil.frequency);
            processSetting.put("triggerDetail", ProcessTimeUtil.triggerDetail);
            processSetting.put("frequencyDetail", ProcessTimeUtil.frequencyDetail);
            return new ResponseEntity<>(new ResponseDto("SUCCESS", "Fetch Process Setting Successfully",
                processSetting), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto("ERROR", "Sorry Process Setting Not Fetch"),
                HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * This method use to check is job exist with name and job is active
     * if job is not active it will allow the user to save the job
     * @param jobName
     * @return ResponseEntity<?> isJobExist
     */
    @RequestMapping(value = "/isJobExist", method = RequestMethod.GET)
    public ResponseEntity<?> isJobExist(@RequestParam(name = "jobName", required = true) String jobName) {
        logger.info("##### isJobExist Start");
        try {
            Optional<Job> jobOptional = this.transactionService.findByJobNameAndJobStatus(jobName, Status.Active);
            if (jobOptional.isPresent()) {
                return new ResponseEntity<>(new ResponseDto("ERROR", "Jobs Already Exist"), HttpStatus.OK);
            } else {
                return new ResponseEntity<>(new ResponseDto("SUCCESS", "Job Not Exist"), HttpStatus.OK);
            }
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto("ERROR", "Sorry Jobs Not Create"),
                HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * This method use to create the jobs for process
     * @param processDtos
     * @return ResponseEntity<?> createJobs
     */
    @RequestMapping(value = "/createJobs", method = RequestMethod.POST)
    public ResponseEntity<?> createJobs(@RequestBody List<ProcessDto> processDtos) {
        logger.info("##### createJobs Start");
        try {
            processDtos.parallelStream().forEach(processDto -> {
                if (processDto.getJob() != null && processDto.getScheduler() != null) {
                    this.transactionService.saveJob(processDto.getJob());
                    this.transactionService.saveScheduler(processDto.getScheduler());
                }
            });
            return new ResponseEntity<>(new ResponseDto("SUCCESS", "Jobs Create Successfully",
                processDtos), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto("ERROR", "Sorry Jobs Not Create"),
                HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * This method use to get the jobs for process
     * @param page
     * @param size
     * @return ResponseEntity<?> getJobs
     */
    @RequestMapping(value = "/getJobs", method = RequestMethod.GET)
    public ResponseEntity<?> getJobs(@RequestParam(name = "page", defaultValue = "0") int page,
           @RequestParam(name = "size", defaultValue = "10") int size) {
        logger.info("##### getJobs Start");
        try {
            PageRequest pageRequest = PageRequest.of(page, size);
            Page<JobViewProjection> pageResult = this.transactionService.getJobs(page, size);
            return new ResponseEntity<>(new ResponseDto("SUCCESS", "Jobs Fetch Successfully",
            new PageImpl<>(Arrays.asList(pageResult.getContent().toArray()), pageRequest,
                pageResult.getTotalElements())), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto("ERROR", "Sorry Jobs Fetch Contact With Support"),
                HttpStatus.BAD_REQUEST);
        }
    }

}
