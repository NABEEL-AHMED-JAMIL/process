package process.util.excel;


/**
 * @author Nabeel Ahmed
 */
public interface ExcelUtil {

    public static String BLANK_VAL = "";
    public static String SHEET_NAME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static String LOOKUP = "LookupTemplate";
    public static String STT = "SourceTaskType";
    public static String STT_FORM = "SourceTaskTypeForm";
    public static String STT_SECTION = "SourceTaskTypeSection";
    public static String STT_CONTROL = "SourceTaskTypeControl";
    public static String BATCH = "Batch.xlsx";
    public static String[] LOOKUP_HEADER_FILED_BATCH_FILE = new String[] {
        "LOOKUP_TYPE", "LOOKUP_VALUE", "DESCRIPTION"
    };
    public static String[] STT_HEADER_FILED_BATCH_FILE = new String[] {
        "SERVICE_NAME", "DESCRIPTION", "DEFAULT", "TASK_TYPE",
        "TOPIC_NAME", "PARTITIONS"
    };
    public static String[] STTF_HEADER_FILED_BATCH_FILE = new String[] {
        "FORM_NAME", "DESCRIPTION", "DEFAULT"
    };
    public static String[] STTS_HEADER_FILED_BATCH_FILE = new String[] {
        "SECTION_ORDER", "SECTION_NAME", "DESCRIPTION", "DEFAULT"
    };
    public static String[] STTC_HEADER_FILED_BATCH_FILE = new String[] {
        "CONTROL_ORDER", "CONTROL_NAME", "DESCRIPTION", "FILED_NAME", "FILED_TITLE",
        "FILED_WIDTH", "PLACEHOLDER", "PATTERN", "FILED_TYPE",
        "MIN_LENGTH", "MAX_LENGTH", "REQUIRED"
    };
    public static String XLSX_EXTENSION = ".xlsx";
}



