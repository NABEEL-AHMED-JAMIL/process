package process.util;

import org.apache.kafka.common.header.Headers;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.time.format.DateTimeFormatter;
import java.util.stream.StreamSupport;

/**
 * @author Nabeel Ahmed
 */
public class ProcessUtil {

    public static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static String START = "*";
    public static String SIMPLE_DATE_PATTERN = "yyyy-MM-dd";
    public static String CONTENT_DISPOSITION ="Content-Disposition";
    public static String FILE_NAME_HEADER = "attachment; filename=";
    public static String KARACHI_TIME_ZONE = "Asia/Karachi";
    public static String QATAR_TIME_ZONE = "Asia/Qatar";
    public static String JOB_QUEUE = "jobQueue";
    public static String TASK_DETAIL = "taskDetail";
    public static String PRIORITY = "Priority";
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

    public static String typeIdHeader(Headers headers) {
        return StreamSupport.stream(headers.spliterator(), false)
            .filter(header -> header.key().equals("__TypeId__"))
            .findFirst().map(header -> new String(header.value())).orElse("N/A");
    }

    public static boolean isNull(Object payload) {
        return payload == null || payload == "" ? true : false;
    }

    public static void timeZoneUtil() {
        String[]ids = TimeZone.getAvailableIDs ();
        for (String id:ids) {
            System.out.println (displayTimeZone (TimeZone.getTimeZone (id)));
        }
        System.out.println ("\nTotal TimeZone ID " + ids.length);
    }
    private static String displayTimeZone (TimeZone tz) {
        long hours = TimeUnit.MILLISECONDS.toHours (tz.getRawOffset ());
        long minutes = TimeUnit.MILLISECONDS.toMinutes (tz.getRawOffset ()) - TimeUnit.HOURS.toMinutes (hours);
        // avoid -4:-30 issue
        minutes = Math.abs (minutes);
        String result = "";
        if (hours > 0) {
            result = String.format ("(GMT+%d:%02d) %s :: %s", hours, minutes, tz.getID(), tz.getDisplayName());
        } else
        {
            result = String.format ("(GMT%d:%02d) %s :: %s", hours, minutes, tz.getID(), tz.getDisplayName());
        }
        return result;
    }

}