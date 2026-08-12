package process.util;

import java.time.format.DateTimeFormatter;

/**
 * @author Nabeel Ahmed
 */
public class ProcessUtil {

    public static String INTERNAL_ERROR_500 = "Some internal error occurred contact with support.";
    public static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static String START = "*";
    public static String ERROR_MESSAGE = "ERROR";
    public static String SIMPLE_DATE_PATTERN = "yyyy-MM-dd";
    public static String CONTENT_DISPOSITION ="Content-Disposition";
    public static String FILE_NAME_HEADER = "attachment; filename=";
    public static String QUEUE_FETCH_LIMIT = "QUEUE_FETCH_LIMIT";
    public static String SCHEDULER_LAST_RUN_TIME = "SCHEDULER_LAST_RUN_TIME";
    public static String EMAIL_RECEIVER = "EMAIL_RECEIVER";
    public static String JOB_STATUS_INVALID = "Job status must be Running, Failed, or Completed";
    public static String JOB_STATUS_MESSAGE_REQUIRED = "Job status message is required for failed and completed job.";
    public static String BAD_REQUEST_400 = "Bad request.";
    public static String TASK_ID = "taskId";
    public static String JOB_QUEUE = "jobQueue";
    public static String TASK_DETAIL = "taskDetail";
    public static String PRIORITY = "priority";
    public static String SHEET_NAME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static String REAL_FILE_PATH = "Scheduler.xlsx";
    public static String XLSX_EXTENSION = ".xlsx";
    public static String ERROR = "ERROR";
    public static String SUCCESS = "SUCCESS";
    public static String JOB_ADD = "Job-Add";

    public static String[] HEADER_FILED_BATCH_FILE = new String[] {
        "Job Name", "Task Detail Id", "Start Date", "End Date", "Start Time",
        "Frequency", "Recurrence", "Priority", "Email Job Complete",
        "Email Job Fail", "Email Job Skip"
    };

    public static String[] HEADER_FILED_BATCH_DOWNLOAD_FILE = new String[] {
        "Job Name", "Task", "Execution", "Priority",  "Status", "Created Date",
        "Start Date", "End Date", "Time", "Last Run", "Next Flight",
        "R-Status", "Email job complete", "Email job fail", "Email job skip"
    };

    /**
     * Method use to check whether a value is "empty" for this codebase's purposes -- null, or
     * an empty string. Used to be "payload == ''", comparing object identity rather than
     * content: that only matched a String literal from THIS class's own compiled bytecode (the
     * JVM interns those into one shared instance), never a runtime-built empty string like the
     * ones Apache POI's cell readers hand back for a blank spreadsheet cell (BulkExcel's
     * getCellDetail, DataFormatter#formatCellValue) or String.trim()/substring() produce
     * elsewhere -- those are distinct objects that happen to hold "", so "==" was always false
     * for them. Every caller across the codebase relies on isNull() to treat that runtime ""
     * the same as null (e.g. "skip validation/enrichment when this optional field is blank");
     * with "==", those blank-but-not-null values silently passed every isNull() check as if
     * they held real content -- for a bulk-uploaded SourceTask with an intentionally blank
     * PipelineId/HomePageId column, that meant listSourceJob's DTO mapping went on to call
     * Long.valueOf("") on it and threw NumberFormatException, a bug otherwise invisible until an
     * actual end-to-end run through bulk upload -> list surfaced it. "".equals(payload) compares
     * content (and is null-safe on the receiver side since it's called on the literal), fixing
     * every one of isNull()'s callers at once.
     * @param payload
     * @return boolean
     * */
    public static boolean isNull(Object payload) {
        return payload == null || "".equals(payload);
    }

}