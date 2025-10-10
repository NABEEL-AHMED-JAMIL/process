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

    public static boolean isNull(Object payload) {
        return payload == null || payload == "";
    }

}