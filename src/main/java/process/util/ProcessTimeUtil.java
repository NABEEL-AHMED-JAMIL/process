package process.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author Nabeel Ahmed
 * This class use to handle the all-time realted procss
 */
@Component
public class ProcessTimeUtil {

    private Logger logger = LoggerFactory.getLogger(ProcessTimeUtil.class);

    public static List<String> checked = Arrays.asList("True", "False");
    public static List<String> priority = Stream.concat(IntStream.rangeClosed(1, 9)
         .mapToObj(String::valueOf), Stream.of("99", "100")).collect(Collectors.toList());
    public static List<String> frequency = Arrays.asList("Mint", "Hr", "Daily", "Weekly", "Monthly");
    // frequency-detail
    public static Map<String, List<?>> frequencyDetail = new HashMap<>();

    static {
        frequencyDetail.put("Mint", getMints());
        frequencyDetail.put("Hr", getHr());
        frequencyDetail.put("Daily", getDaily());
        frequencyDetail.put("Weekly", getWeekly());
        frequencyDetail.put("Monthly", getMonthly());
    }

    /**
     * this method return the mints for scheduler daily
     * @return List getMints
     * */
    private static List getMints() {
        return Arrays.asList(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55);
    }

    /**
     * this method return the Hr for scheduler daily
     * @return List getHr
     * */
    private static List getHr() {
        return Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }

    /**
     * this method return the daily for scheduler daily
     * @return List getDaily
     * */
    private static List getDaily() {
        return Arrays.asList(1, 2, 3, 4, 5, 6);
    }

    /**
     * this method return the Week-day for scheduler
     * @return List getWeekly
     * */
    private static List getWeekly() {
        return Arrays.asList(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31);
    }

    /**
     * this method return the Monthly-Day for scheduler
     * @return List getMonthly
     * */
    private static List getMonthly() {
        return Arrays.asList(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
        );
    }

    /**
     * This method use to get the date and time
     * @param date
     * @param startTime
     * @return LocalDateTime
     * */
    public static LocalDateTime getRecurrenceTime(LocalDate date, String startTime) {
        String[] timeSplit = startTime.split(":");
        return date.atStartOfDay().plusHours(Integer.parseInt(timeSplit[0])).plusMinutes(Integer.parseInt(timeSplit[1]));
    }

}