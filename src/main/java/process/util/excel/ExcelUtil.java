package process.util.excel;

/**
 * @author Nabeel Ahmed
 */
public interface ExcelUtil {

    public static String SHEET_NAME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static String LOOKUP = "LookupTemplate";
    public static String LOOKUP_TEMPLATE = "LookupTemplate.xlsx";
    public static String[] LOOKUP_HEADER_FILED_BATCH_FILE = new String[] {
        "LOOKUP_TYPE", "LOOKUP_VALUE", "DESCRIPTION"
    };
    public static String XLSX_EXTENSION = ".xlsx";
}
