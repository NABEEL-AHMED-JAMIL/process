package process.model.service;

import org.springframework.data.domain.Pageable;
import process.model.dto.ResponseDto;
import process.model.dto.SearchTextDto;
import process.model.dto.TaskDetailDto;

/**
 * @author Nabeel Ahmed
 */
public interface SourceTaskApiService {

    public ResponseDto addSourceTask(TaskDetailDto tempTaskDetail) throws Exception;

    public ResponseDto updateSourceTask(TaskDetailDto tempTaskDetail) throws Exception;

    public ResponseDto listSourceTask(String startDate, String endDate, Pageable paging,
          SearchTextDto searchTextDto) throws Exception;

    public ResponseDto fetchAllLinkJobsWithSourceTask(Long taskDetailId) throws Exception;

}
