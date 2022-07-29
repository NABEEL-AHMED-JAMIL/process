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

    public ResponseDto deleteSourceTask(TaskDetailDto tempTaskDetail) throws Exception;

    public ResponseDto listSourceTask(Long appUserId, String startDate, String endDate,
          String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception;

    public ResponseDto fetchAllLinkJobsWithSourceTask(Long appUserId, Long taskDetailId, String startDate, String endDate,
          String columnName, String order, Pageable paging, SearchTextDto searchTextDt) throws Exception;

    public ResponseDto fetchSourceTaskDetailWithSourceTaskId(Long taskDetailId);

    public ResponseDto downloadListSourceTask(Long appUserId, String startDate, String endDate,
          String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception;

}
