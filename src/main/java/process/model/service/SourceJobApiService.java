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

    public ResponseDto listSourceJob(String startDate, String endDate, Pageable paging,
         SearchTextDto searchTextDto) throws Exception;
}
