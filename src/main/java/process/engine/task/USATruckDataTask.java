package process.engine.task;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import process.efs.EfsFileExchange;
import process.emailer.EmailMessagesFactory;
import process.engine.BulkAction;
import process.engine.parser.TruckData;
import process.model.dto.SourceJobQueueDto;
import process.model.dto.SourceTaskDto;
import process.model.enums.JobStatus;
import process.model.service.impl.TransactionServiceImpl;
import process.mongo.documents.FileInfo;
import process.mongo.documents.HashMeta;
import process.mongo.documents.RawData;
import process.mongo.repository.FileInfoRepository;
import process.util.ProcessUtil;
import process.util.XmlOutTagInfoUtil;
import process.util.exception.ExceptionUtil;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * @author Nabeel Ahmed
 */
@Component
public class USATruckDataTask implements Runnable {

    public Logger logger = LogManager.getLogger(USATruckDataTask.class);

    private Map<String, Object> data;
    @Autowired
    private BulkAction bulkAction;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private EmailMessagesFactory emailMessagesFactory;
    @Autowired
    private XmlOutTagInfoUtil xmlOutTagInfoUtil;
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private EfsFileExchange efsFileExchange;

    @Override
    public void run() {
        // change the status into the running status
        SourceJobQueueDto jobQueue = (SourceJobQueueDto) this.getData().get(ProcessUtil.JOB_QUEUE);
        SourceTaskDto sourceTaskDto = (SourceTaskDto) this.getData().get(ProcessUtil.TASK_DETAIL);
        try {
            this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Running);
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Running);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the running.", jobQueue.getJobId()));
            this.bulkAction.sendJobStatusNotification(jobQueue.getJobId().intValue(), (String)this.getData().get(ProcessUtil.TRANSACTION_ID));
            TruckData truckData = this.xmlOutTagInfoUtil.convertXMLToObject(sourceTaskDto.getTaskPayload(), TruckData.class);
            if (ProcessUtil.isNull(truckData.getRootFolder())) {
                throw new Exception("TruckData rootFolder not defined.");
            }
            // fetch the lookup data and set the real value into object and set the base path
            truckData.setRootFolder(this.transactionService.findByLookupType(truckData.getRootFolder()).getLookupValue());
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job id %s with queue id %s using root folder %s.",
                jobQueue.getJobId(), jobQueue.getJobQueueId(), truckData.getRootFolder()));
            this.efsFileExchange.setBasePathTempDire(truckData.getRootFolder());
            if (ProcessUtil.isNull(truckData.getOutputFolder())) {
                throw new Exception("TruckData outputFolder not defined.");
            }
            // fetch the lookup data and set output folder and then make the dir if exist it return true or else make and then true if failed send false
            truckData.setOutputFolder(this.transactionService.findByLookupType(truckData.getOutputFolder()).getLookupValue());
            if (!this.efsFileExchange.makeDir(truckData.getOutputFolder())) {
                throw new Exception(String.format("Failed to make output folder [%s].", truckData.getOutputFolder()));
            }
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job id %s with queue id %s using output folder %s.",
                jobQueue.getJobId(), jobQueue.getJobQueueId(), truckData.getOutputFolder()));
            if (ProcessUtil.isNull(truckData.getFolderPattern())) {
                throw new Exception("TruckData folderPattern not defined.");
            }
            // check the folder pattern for input file
            truckData.setFolderPattern(this.transactionService.findByLookupType(truckData.getFolderPattern()).getLookupValue());
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job id %s with queue id %s using file pattern %s.",
                    jobQueue.getJobId(), jobQueue.getJobQueueId(), truckData.getFolderPattern()));
            // process for the current job.....
            while (true) {
                List<RawData> rawData = (this.fileInfoRepository.getFileInfoCountByJobId(jobQueue.getJobId()) > 0) ?
                    this.fetchRawDataDetail(Boolean.TRUE, truckData, jobQueue) : this.fetchRawDataDetail(Boolean.FALSE, truckData, jobQueue);
                // if the rawData is not null and size for rawData is empty then break the process
                if (!ProcessUtil.isNull(rawData) & rawData.isEmpty()) {
                    break;
                }
                rawData.stream().forEach(inputRawData -> {
                    try {
                        this.processRawFile(inputRawData, jobQueue, truckData);
                    } catch (Exception ex) {
                        logger.info("Exception " + ExceptionUtil.getRootCauseMessage(ex));
                    }
                });
            }
            // change the status into the complete status
            this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Completed);
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Completed);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now complete.", jobQueue.getJobId()));
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
            this.bulkAction.sendJobStatusNotification(jobQueue.getJobId().intValue(), (String)this.getData().get(ProcessUtil.TRANSACTION_ID));
            if (this.transactionService.findByJobId(jobQueue.getJobId()).get().isCompleteJob()) {
                this.emailMessagesFactory.sendSourceJobEmail(jobQueue,JobStatus.Completed);
            }
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            // change the status into the fail status
            this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Failed);
            this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Failed);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s fail due to %s .",
                    jobQueue.getJobId(), ExceptionUtil.getRootCauseMessage(ex)));
            this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
            this.bulkAction.sendJobStatusNotification(jobQueue.getJobId().intValue(), (String)this.getData().get(ProcessUtil.TRANSACTION_ID));
            if (this.transactionService.findByJobId(jobQueue.getJobId()).get().isFailJob()) {
                this.emailMessagesFactory.sendSourceJobEmail(jobQueue,JobStatus.Failed);
            }
        }
    }

    /**
     * Method use to fetch the raw data detail
     * @param existDataForJob
     * @param truckData
     * @param jobQueue
     * @return List<?>
     * **/
    private List<RawData> fetchRawDataDetail(Boolean existDataForJob, TruckData truckData, SourceJobQueueDto jobQueue) {
        List<RawData> rawData = new ArrayList<>();
        if (existDataForJob) {
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job id %s with queue id %s, previous iteration have data for job id",
                jobQueue.getJobId(), jobQueue.getJobQueueId()));
            rawData = this.mongoTemplate.aggregate(Aggregation.newAggregation(
                Aggregation.lookup("fileInfo", "tag", "remoteFileTag", "matchingFiles"),
                Aggregation.match(Criteria.where("matchingFiles.jobId").ne(jobQueue.getJobId())),
                Aggregation.project("id", "folderPath", "filePath", "tag", "created"),
                Aggregation.limit(truckData.getFetchLimit())), "rawData", RawData.class).getMappedResults();
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job id %s with queue id %s found total %s result.",
                jobQueue.getJobId(), jobQueue.getJobQueueId(), rawData.size()));
        } else {
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job id %s with queue id %s fetch data from root.",
                jobQueue.getJobId(), jobQueue.getJobQueueId()));
            Criteria folderPatternCriteria = where("folderPath").regex(truckData.getFolderPattern());
            Criteria validTypeCriteria = where("filePath").regex(truckData.getValidType());
            Criteria finalCriteria = new Criteria().andOperator(folderPatternCriteria, validTypeCriteria);
            Query query = new Query(finalCriteria);
            rawData = this.mongoTemplate.find(query.limit(truckData.getFetchLimit()), RawData.class);
            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job id %s with queue id %s found total %s result.",
                jobQueue.getJobId(), jobQueue.getJobQueueId(), rawData.size()));
        }
        return rawData;
    }

    /**
     * Read the file and convert into xml and make the hash code for
     * file and store the both file in the target folder with source job & queue id
     * method use to convert the raw txt file and parse the gson and convert into xml
     * @param rawData
     * @param jobQueue
     * @param truckData
     * */
    private void processRawFile(RawData rawData, SourceJobQueueDto jobQueue, TruckData truckData) throws Exception {
        logger.info("Processing rawData file " + rawData);
        InputStream inputStream = this.efsFileExchange.getFile(rawData.getFilePath());
        String result = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        if (!ProcessUtil.isNull(result)) {
            inputStream.close();
            String xmlString = this.convertJsonObjectToXml(JsonParser.parseString(result).getAsJsonObject());
            logger.info("XML detail " + xmlString);
            FileInfo fileInfo = new FileInfo();
            fileInfo.setJobId(jobQueue.getJobId());
            fileInfo.setJobQueueId(jobQueue.getJobQueueId());
            fileInfo.setLastDate(System.currentTimeMillis()+"ms");
            fileInfo.setFileAccess(Boolean.TRUE);
            fileInfo.setRemoteFileName(this.getLastNameName(rawData.getFilePath()));
            fileInfo.setRemoteFileUrl(rawData.getFilePath());
            fileInfo.setRemoteFileTag(rawData.getTag());
            if (!ProcessUtil.isNull(truckData.getOutputFolder()) && (this.efsFileExchange.makeDir(truckData.getOutputFolder()) &&
                this.efsFileExchange.makeDir(truckData.getOutputFolder()+"\\"+jobQueue.getJobId()+"\\"+this.getLastNameName(rawData.getFolderPath())))) {
                String storeFile = fileInfo.getRemoteFileName();
                storeFile = storeFile.replace(".txt", ".xml");
                fileInfo.setStoreFileName(storeFile);
                fileInfo.setStoreFileUrl(truckData.getRootFolder() + truckData.getOutputFolder() + "\\" + jobQueue.getJobId()
                    + "\\" + this.getLastNameName(rawData.getFolderPath()) + "\\" + storeFile);
                byte[] xmlBytes = xmlString.getBytes();
                ByteArrayOutputStream baos = new ByteArrayOutputStream(xmlBytes.length);
                baos.write(xmlBytes, 0, xmlBytes.length);
                this.efsFileExchange.saveFile(baos, truckData.getOutputFolder() + "\\" + jobQueue.getJobId()
                    + "\\" + this.getLastNameName(rawData.getFolderPath()) + "\\" + storeFile);
                HashMeta hashMeta = new HashMeta();
                hashMeta.setHashTag(this.efsFileExchange.bytesToHex(xmlBytes));
                fileInfo.setHashMeta(hashMeta);
                this.fileInfoRepository.save(fileInfo);
                this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job id %s with queue id %s, file save %s.",
                    jobQueue.getJobId(), jobQueue.getJobQueueId(), fileInfo.getStoreFileUrl()));
                return;
            }
            logger.info("Output directory have issue %s", truckData::getOutputFolder);
        }
    }

    /**
     * Get fileName
     * @param filePath
     * */
    private static String getLastNameName(String filePath) {
        int lastSlashIndex = filePath.lastIndexOf('/');
        if (lastSlashIndex >= 0 && lastSlashIndex < filePath.length() - 1) {
            return filePath.substring(lastSlashIndex + 1);
        }
        return "";
    }


    /**
     * Method use to convert json object to xml
     * @param jsonObject
     * */
    private static String convertJsonObjectToXml(JsonObject jsonObject) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<root>\n");
        sb.append(convertJsonObjectToXmlRecursively(jsonObject));
        sb.append("</root>");
        return sb.toString();
    }

    /**
     * Recursive function to traverse JsonObject and build XML
     * Method use to convert json object to xml
     * @param jsonObject
     * */
    private static String convertJsonObjectToXmlRecursively(JsonObject jsonObject) {
        StringBuilder sb = new StringBuilder();
        for (String key : jsonObject.keySet()) {
            Object value = jsonObject.get(key);
            sb.append("<").append(key).append(">");
            if (value instanceof JsonObject) {
                sb.append(convertJsonObjectToXmlRecursively((JsonObject) value));
            } else {
                sb.append(value);
            }
            sb.append("</").append(key).append(">\n");
        }
        return sb.toString();
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
