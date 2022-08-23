package process.model.service;

import org.springframework.data.domain.Pageable;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.dto.SearchTextDto;
import process.model.dto.SourceTaskDto;
import java.io.ByteArrayOutputStream;

/**
 * @author Nabeel Ahmed
 */
public interface SourceTaskApiService {

    public ResponseDto addSourceTask(SourceTaskDto tempSourceTask) throws Exception;

    public ResponseDto updateSourceTask(SourceTaskDto tempSourceTask) throws Exception;

    public ResponseDto deleteSourceTask(SourceTaskDto tempSourceTask) throws Exception;

    public ResponseDto listSourceTask(Long appUserId, String startDate, String endDate,
          String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception;

    public ResponseDto fetchAllLinkJobsWithSourceTaskId(Long appUserId, Long sourceTaskId, String startDate, String endDate,
          String columnName, String order, Pageable paging, SearchTextDto searchTextDt) throws Exception;

    public ResponseDto fetchSourceTaskWithSourceTaskId(Long sourceTaskId);

    public ResponseDto fetchAllLinkSourceTaskWithSourceTaskTypeId(Long sourceTaskTypeId) throws Exception;

    public ByteArrayOutputStream downloadListSourceTask() throws Exception;

    public ByteArrayOutputStream downloadSourceTaskTemplate() throws Exception;

    public ResponseDto uploadSourceTask(FileUploadDto fileUploadDto) throws Exception;

}
