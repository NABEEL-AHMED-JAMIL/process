package process.service.imp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import process.payload.request.SearchTextRequest;
import process.payload.request.SourceTaskRequest;
import process.payload.response.AppResponse;
import process.service.SourceTaskApiService;
import java.io.ByteArrayOutputStream;

/**
 * @author Nabeel Ahmed
 */
@Service
public class SourceTaskApiServiceImpl implements SourceTaskApiService {

    private Logger logger = LoggerFactory.getLogger(SourceTaskApiServiceImpl.class);

    /**
     * Method use to add source task
     * @param payload
     * @return AppResponse
     * */
    @Override
    public AppResponse addSourceTask(SourceTaskRequest payload) throws Exception {
        return null;
    }

    @Override
    public AppResponse editSourceTask(SourceTaskRequest tempSourceTask) throws Exception {
        return null;
    }

    @Override
    public AppResponse deleteSourceTask(SourceTaskRequest tempSourceTask) throws Exception {
        return null;
    }

    @Override
    public AppResponse listSourceTask(Long appUserId, String startDate, String endDate, String columnName,
        String order, Pageable paging, SearchTextRequest searchText) throws Exception {
        return null;
    }

    @Override
    public AppResponse fetchAllLinkJobsWithSourceTaskId(Long appUserId, Long sourceTaskId, String startDate,
        String endDate, String columnName, String order, Pageable paging,
        SearchTextRequest searchText) throws Exception {
        return null;
    }

    @Override
    public AppResponse fetchSourceTaskWithSourceTaskId(Long sourceTaskId) {
        return null;
    }

    @Override
    public AppResponse fetchAllLinkSourceTaskWithSourceTaskTypeId(Long sourceTaskTypeId) throws Exception {
        return null;
    }

    @Override
    public ByteArrayOutputStream downloadListSourceTask() throws Exception {
        return null;
    }
}
