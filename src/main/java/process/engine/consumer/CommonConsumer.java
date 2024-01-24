package process.engine.consumer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.engine.BulkAction;
import process.engine.async.executor.AsyncDALTaskExecutor;
import process.model.enums.JobStatus;
import process.model.payload.response.SourceJobQueueResponse;
import process.model.service.LookupDataCacheService;
import process.util.ProcessUtil;
import java.util.HashMap;
import java.util.Map;


/**
 * @author Nabeel Ahmed
 */
@Component
public class CommonConsumer {

    @Autowired
    private LookupDataCacheService lookupDataCacheService;
    @Autowired
    private AsyncDALTaskExecutor asyncDALTaskExecutor;
    @Autowired
    private BulkAction bulkAction;

    public CommonConsumer() {
    }

    public LookupDataCacheService getLookupDataCacheService() {
        return lookupDataCacheService;
    }

    public void setLookupDataCacheService(LookupDataCacheService lookupDataCacheService) {
        this.lookupDataCacheService = lookupDataCacheService;
    }

    public AsyncDALTaskExecutor getAsyncDALTaskExecutor() {
        return asyncDALTaskExecutor;
    }

    public void setAsyncDALTaskExecutor(AsyncDALTaskExecutor asyncDALTaskExecutor) {
        this.asyncDALTaskExecutor = asyncDALTaskExecutor;
    }

    public BulkAction getBulkAction() {
        return bulkAction;
    }

    public void setBulkAction(BulkAction bulkAction) {
        this.bulkAction = bulkAction;
    }

    /**
     * Method use to fill task detail
     * @param payload
     * @return Map<String, Object>
     * */
    public Map<String, Object> fillTaskDetail(JsonObject payload) throws Exception {
        SourceJobQueueResponse sourceJobQueueResponse = new Gson().fromJson(payload.get(ProcessUtil.JOB_QUEUE), SourceJobQueueResponse.class);
        this.getBulkAction().changeJobStatus(sourceJobQueueResponse.getJobId(), JobStatus.Start);
        this.getBulkAction().sendJobStatusNotification(sourceJobQueueResponse.getJobId().intValue(),
            this.getLookupDataCacheService().getParentLookupById(ProcessUtil.TRANSACTION_ID).getLookupValue());
        Map<String, Object> taskPayloadInfo = new HashMap<>();
        taskPayloadInfo.put(ProcessUtil.JOB_QUEUE, sourceJobQueueResponse);
        taskPayloadInfo.put(ProcessUtil.TASK_DETAIL, new Gson().fromJson(payload.get(ProcessUtil.TASK_DETAIL), SourceJobQueueResponse.class));
        taskPayloadInfo.put(ProcessUtil.TRANSACTION_ID, this.getLookupDataCacheService().getParentLookupById(ProcessUtil.TRANSACTION_ID).getLookupValue());
        return taskPayloadInfo;
    }
}