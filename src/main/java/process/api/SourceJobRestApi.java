package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.dto.SearchTextDto;
import process.model.dto.SourceJobDto;
import process.model.service.SourceJobApiService;
import process.util.PagingUtil;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * Api use to perform crud operation on source job
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/sourceJob.json")
public class SourceJobRestApi {

    private Logger logger = LoggerFactory.getLogger(SourceJobRestApi.class);

    @Autowired
    private SourceJobApiService sourceJobApiService;

    @RequestMapping(value = "/addSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceJob(@RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.addSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                    "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/updateSourceJob", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSourceJob(@RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.updateSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                    "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/listSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> listSourceJob(@RequestParam(value = "page") Long page, @RequestParam(value = "limit") Long limit,
       @RequestParam(value = "startDate") String startDate, @RequestParam(value = "endDate") String endDate,
       @RequestParam(value = "columnName") String columnName, @RequestParam(value = "order") String order,
       @RequestBody SearchTextDto searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.listSourceJob(startDate, endDate,
                    PagingUtil.ApplyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                    "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }
}
