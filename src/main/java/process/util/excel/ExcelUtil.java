package process.util.excel;


/**
 * @author Nabeel Ahmed
 */
public interface ExcelUtil {

    public static String BLANK_VAL = "";
    public static String SHEET_NAME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static String APP_USER = "AppUser";
    public static String LOOKUP = "LookupTemplate";
    public static String STT = "SourceTaskType";
    public static String STT_FORM = "SourceTaskTypeForm";
    public static String STT_SECTION = "SourceTaskTypeSection";
    public static String STT_CONTROL = "SourceTaskTypeControl";
    public static String BATCH = "Batch.xlsx";

    public static String[] APP_USER_HEADER_FIELD_BATCH_FILE = new String[] {
        "FIRSTNAME", "LASTNAME", "TIMEZONE", "USERNAME", "EMAIL", "PASSWORD"
    };
    public static String[] LOOKUP_HEADER_FIELD_BATCH_FILE = new String[] {
        "LOOKUP_TYPE", "LOOKUP_VALUE", "DESCRIPTION"
    };
    public static String[] STT_HEADER_FIELD_BATCH_FILE = new String[] {
        "SERVICE_NAME", "DESCRIPTION", "DEFAULT", "TASK_TYPE",
        "TOPIC_NAME", "PARTITIONS"
    };
    public static String[] STTF_HEADER_FIELD_BATCH_FILE = new String[] {
        "FORM_NAME", "DESCRIPTION", "DEFAULT", "FORM_TYPE"
    };
    public static String[] STTS_HEADER_FIELD_BATCH_FILE = new String[] {
        "SECTION_NAME", "DESCRIPTION", "DEFAULT"
    };
    public static String[] STTC_HEADER_FIELD_BATCH_FILE = new String[] {
        "CONTROL_NAME", "DESCRIPTION", "FIELD_NAME", "FIELD_TITLE",
        "FIELD_WIDTH", "PLACEHOLDER", "PATTERN", "FIELD_TYPE",
        "MIN_LENGTH", "MAX_LENGTH", "REQUIRED"
    };

    public static String[] HEADER_FIELD_BATCH_FILE = new String[] {
            "Job Name", "Task Detail Id", "Start Date", "End Date", "Start Time",
            "Frequency", "Recurrence", "Priority", "Email Job Complete",
            "Email Job Fail", "Email Job Skip"
    };

    public static String[] HEADER_FIELD_BATCH_DOWNLOAD_FILE = new String[] {
            "Job Name", "Task", "Execution", "Priority",  "Status", "Created Date",
            "Start Date", "End Date", "Time", "Last Run", "Next Flight",
            "R-Status", "Email job complete", "Email job fail", "Email job skip"
    };

    public static String XLSX_EXTENSION = ".xlsx";
}



