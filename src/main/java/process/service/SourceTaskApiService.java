package process.service;

import org.springframework.data.domain.Pageable;
import process.payload.request.SearchTextRequest;
import process.payload.request.SourceTaskRequest;
import process.payload.response.AppResponse;
import java.io.ByteArrayOutputStream;

/**
 * @author Nabeel Ahmed
 */
public interface SourceTaskApiService {

    public AppResponse addSourceTask(SourceTaskRequest tempSourceTask) throws Exception;

    public AppResponse editSourceTask(SourceTaskRequest tempSourceTask) throws Exception;

    public AppResponse deleteSourceTask(SourceTaskRequest tempSourceTask) throws Exception;

    public AppResponse listSourceTask(Long appUserId, String startDate, String endDate, String columnName,
        String order, Pageable paging, SearchTextRequest searchText) throws Exception;

    public AppResponse fetchAllLinkJobsWithSourceTaskId(Long appUserId, Long sourceTaskId, String startDate, String endDate,
        String columnName, String order, Pageable paging, SearchTextRequest searchText) throws Exception;

    public AppResponse fetchSourceTaskWithSourceTaskId(Long sourceTaskId);

    public AppResponse fetchAllLinkSourceTaskWithSourceTaskTypeId(Long sourceTaskTypeId) throws Exception;

    public ByteArrayOutputStream downloadListSourceTask() throws Exception;

}