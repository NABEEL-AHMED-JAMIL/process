package process.util;

import org.apache.kafka.common.header.Headers;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import process.model.pojo.AppUser;
import process.security.UserPrincipalDetail;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    public static boolean isValidEmail(String emailStr) {
        Matcher matcher = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,6}$",
            Pattern.CASE_INSENSITIVE).matcher(emailStr);
        if (matcher.find()) {
            return true;
        } else {
            return false;
        }
    }

    public static String getToken() {
        return  "etl-" + (long) Math.floor(Math.random() * 9_000_000_000L) + 1_000_000_000L;
    }

    public static List<SimpleGrantedAuthority> buildSimpleGrantedAuthorities(final Set<String> authorities) {
        List<SimpleGrantedAuthority> grantedAuthorities = new ArrayList<>();
        for (String authority : authorities) {
            grantedAuthorities.add(new SimpleGrantedAuthority(authority));
        }
        return grantedAuthorities;
    }

    public static AppUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        UserPrincipalDetail userPrincipalDetail = (UserPrincipalDetail) authentication.getPrincipal();
        AppUser appUser = userPrincipalDetail.getAppUser();
        return appUser;
    }

    public static boolean isNull(Object payload) {
        return payload == null || payload == "" ? true : false;
    }

}