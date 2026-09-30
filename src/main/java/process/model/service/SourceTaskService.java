package process.model.service;

import org.springframework.data.domain.Pageable;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.dto.SearchTextDto;
import process.model.dto.SourceTaskDto;
import java.io.ByteArrayOutputStream;

/**
 * @author Nabeel Ahmed
 * */
public interface SourceTaskService {

    ResponseDto addSourceTask(SourceTaskDto sourceTaskDto) throws Exception;

    ResponseDto updateSourceTask(SourceTaskDto sourceTaskDto) throws Exception;

    ResponseDto deleteSourceTask(SourceTaskDto sourceTaskDto) throws Exception;

    ResponseDto listSourceTask(String startDate, String endDate,
          String columnName, String order, Pageable paging, SearchTextDto searchTextDto) throws Exception;

    ResponseDto fetchAllLinkJobsWithSourceTaskId(Long sourceTaskId, String startDate, String endDate,
          String columnName, String order, Pageable paging, SearchTextDto searchTextDt) throws Exception;

    ResponseDto fetchSourceTaskWithSourceTaskId(Long sourceTaskId);

    ByteArrayOutputStream downloadListSourceTask() throws Exception;

    ByteArrayOutputStream downloadSourceTaskTemplate() throws Exception;

    ResponseDto uploadSourceTask(FileUploadDto<?> fileUploadDto) throws Exception;

}
