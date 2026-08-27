package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ReportExportRequestDto;
import process.model.dto.ResponseDto;
import process.model.service.impl.ReportExportServiceImpl;
import process.util.ProcessUtil;

/**
 * Exporting a report grid.
 *
 * The work is here rather than in the browser because two of the three destinations are only
 * reachable from the server: a bucket write goes through the storage credentials the browser
 * never sees, and a submit posts from inside the deployment. Building the file server-side as
 * well means all three destinations carry byte-identical content.
 *
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/report.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class ReportRestApi {

    private final Logger logger = LoggerFactory.getLogger(ReportRestApi.class);

    private final ReportExportServiceImpl reportExportService;

    public ReportRestApi(ReportExportServiceImpl reportExportService) {
        this.reportExportService = reportExportService;
    }

    /** The runs a report is built from. The grouping happens in the browser. */
    @RequestMapping(value = "/runs", method = RequestMethod.GET)
    public ResponseEntity<?> runs(
        @RequestParam(name = "startDate", required = false) String startDate,
        @RequestParam(name = "endDate", required = false) String endDate) {
        try {
            return new ResponseEntity<>(this.reportExportService.runRows(startDate, endDate), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while reading report runs", ex);
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/export", method = RequestMethod.POST)
    public ResponseEntity<?> export(@RequestBody ReportExportRequestDto requestDto) {
        try {
            return new ResponseEntity<>(this.reportExportService.export(requestDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while exporting a report", ex);
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
