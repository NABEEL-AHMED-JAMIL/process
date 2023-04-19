package process.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * @author Nabeel Ahmed
 */
public class ProcessUtil {

    public static Logger logger = LoggerFactory.getLogger(ProcessUtil.class);

    public static String SIMPLE_DATE_PATTERN = "yyyy-MM-dd";
    public static String CONTENT_DISPOSITION ="Content-Disposition";
    public static String FILE_NAME_HEADER = "attachment; filename=";
    public static String ERROR = "ERROR";
    public static String SUCCESS = "SUCCESS";
    public static String JOB_ADD = "Job-Add";

    public static boolean isNull(Object payload) {
        return payload == null || payload == "" ? true : false;
    }

    public static void timeZoneUtil() {
        String[]ids = TimeZone.getAvailableIDs();
        for(String id:ids) {
            logger.info(displayTimeZone(TimeZone.getTimeZone(id)));
        }
        logger.info("Total TimeZone ID " + ids.length);
    }

    private static String displayTimeZone(TimeZone tz) {
        long hours = TimeUnit.MILLISECONDS.toHours(tz.getRawOffset());
        long minutes = TimeUnit.MILLISECONDS.toMinutes(tz.getRawOffset()) - TimeUnit.HOURS.toMinutes(hours);
        // avoid -4:-30 issue
        minutes = Math.abs(minutes);
        String result = "";
        if (hours > 0) {
            result = String.format("(GMT+%d:%02d) %s :: %s", hours, minutes, tz.getID(), tz.getDisplayName());
        } else {
            result = String.format("(GMT%d:%02d) %s :: %s", hours, minutes, tz.getID(), tz.getDisplayName());
        }
        return result;
    }

}