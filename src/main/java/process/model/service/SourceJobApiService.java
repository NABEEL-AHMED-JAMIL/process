package process.model.service;

import org.springframework.data.domain.Pageable;
import process.model.dto.ResponseDto;
import process.model.dto.SearchTextDto;
import process.model.dto.SourceJobDto;

/**
 * @author Nabeel Ahmed
 */
public interface SourceJobApiService {

    public ResponseDto addSourceJob(SourceJobDto tempSourceJob) throws Exception;

    public ResponseDto updateSourceJob(SourceJobDto tempSourceJob) throws Exception;

    public ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobDetailId) throws Exception;

    public ResponseDto listSourceJob() throws Exception;

    /**
     * note: this api call ever 1 mint from the ui by using the timer and update the below detail on the table
     * jobId, jobStatus, Next Flight, Last Run, R-Status
     * */
    public ResponseDto fetchRunningJobEvent(SourceJobDto tempSourceJob) throws Exception;

    public ResponseDto downloadListSourceJob(Long appUserId, String startDate, String endDate,
          String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception;
}
