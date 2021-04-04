package process.model.service;

import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import java.io.ByteArrayInputStream;

/**
 * @author Nabeel Ahmed
 */
public interface SchedulerApiService {

    // constant-filed
    String SHEET_NAME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    String REAL_FILE_PATH = "Scheduler.xlsx";
    String XLSX_EXTENSION = ".xlsx";
    String ERROR = "ERROR";
    String SUCCESS = "SUCCESS";
    String JOB_ADD = "Job-Add";
    String STATIC_SHEET = "ListSheet";
    String CATEGORIES = "Categories";
    String FORMULATE = "INDIRECT($F$2)";
    String WEEKLY = "Weekly";
    String DAILY = "Daily";

    String[] HEADER_FILED_BATCH_FILE = new String[] {
        "Job Name", "Trigger Detail", "Start Date", "End Date",
        "Start Time", "Frequency", "Recurrence"
    };

    public ByteArrayInputStream downloadBatchSchedulerTemplateFile() throws Exception;

    public ResponseDto uploadJobFile(FileUploadDto object) throws Exception;

}
