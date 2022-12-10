package process.util;

import org.apache.kafka.common.header.Headers;
import process.model.dto.SourceJobQueueDto;
import process.model.pojo.JobQueue;

import java.time.format.DateTimeFormatter;
import java.util.stream.StreamSupport;

/**
 * @author Nabeel Ahmed
 */
public class ProcessUtil {

    public static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static String START = "*";
    public static String ERROR_MESSAGE = "ERROR";
    public static String SIMPLE_DATE_PATTERN = "yyyy-MM-dd";
    public static String CONTENT_DISPOSITION ="Content-Disposition";
    public static String FILE_NAME_HEADER = "attachment; filename=";
    public static String KARACHI_TIME_ZONE = "Asia/Karachi";
    public static String QATAR_TIME_ZONE = "Asia/Qatar";
    public static String QUEUE_FETCH_LIMIT = "QUEUE_FETCH_LIMIT";
    public static String SCHEDULER_LAST_RUN_TIME = "SCHEDULER_LAST_RUN_TIME";
    public static String JOB_QUEUE = "jobQueue";
    public static String TASK_DETAIL = "taskDetail";
    public static String PRIORITY = "Priority";

    // constant-filed
    public static String SHEET_NAME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static String REAL_FILE_PATH = "Scheduler.xlsx";
    public static String XLSX_EXTENSION = ".xlsx";
    public static String ERROR = "ERROR";
    public static String SUCCESS = "SUCCESS";
    public static String JOB_ADD = "Job-Add";

    public static String[] HEADER_FILED_BATCH_FILE = new String[] {
        "Job Name", "Task Detail Id", "Start Date", "End Date",
        "Start Time", "Frequency", "Recurrence"
    };

    public static String[] HEADER_FILED_BATCH_DOWNLOAD_FILE = new String[] {
        "Job Name", "Task", "Execution", "Status", "Created Date",
        "Start Date", "End Date", "Time", "Last Run", "Next Flight",
        "R-Status", "Email job complete", "Email job fail", "Email job skip"
    };

    public static String typeIdHeader(Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(header -> header.key().equals("__TypeId__"))
            .findFirst().map(header -> new String(header.value())).orElse("N/A");
    }

    public static SourceJobQueueDto getSourceJobQueueDto(JobQueue jobQueue) {
        SourceJobQueueDto sourceJobQueueDto = new SourceJobQueueDto();
        sourceJobQueueDto.setJobId(jobQueue.getJobId());
        sourceJobQueueDto.setJobQueueId(jobQueue.getJobQueueId());
        sourceJobQueueDto.setStartTime(jobQueue.getStartTime());
        return sourceJobQueueDto;
    }

    public static boolean isNull(Object payload) {
        return payload == null || payload == "" ? true : false;
    }

}