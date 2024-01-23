package process.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author Nabeel Ahmed
 * This class use to handle the all-time realted procss
 */
@Component
public class ProcessTimeUtil {

    private Logger logger = LoggerFactory.getLogger(ProcessTimeUtil.class);

    public static List<String> checked = new ArrayList<>();
    public static List<String> priority = new ArrayList<>();
    public static List<String> frequency = new ArrayList<>();

    public static Map<String, List<?>> frequencyDetail = new HashMap<>();

    static {
        // checked
        checked.add("True");
        checked.add("False");
        // --------------------- //
        priority.add("1");
        priority.add("2");
        priority.add("3");
        priority.add("4");
        priority.add("5");
        priority.add("6");
        priority.add("7");
        priority.add("8");
        priority.add("9");
        priority.add("99");
        priority.add("100");
        // ------------------- //
        frequency.add("Mint");
        frequency.add("Hr");
        frequency.add("Daily");
        frequency.add("Weekly");
        frequency.add("Monthly");
        // --------------------- //
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
        List<Integer> mints = new ArrayList<>();
        mints.add(5);  mints.add(10); mints.add(15);
        mints.add(20); mints.add(25); mints.add(30);
        mints.add(35); mints.add(40); mints.add(45);
        mints.add(50); mints.add(55);
        return mints;
    }

    /**
     * this method return the Hr for scheduler daily
     * @return List getHr
     * */
    private static List getHr() {
        List<Integer> hr = new ArrayList<>();
        hr.add(1); hr.add(2); hr.add(3); hr.add(4);
        hr.add(5); hr.add(6); hr.add(7); hr.add(8);
        hr.add(9); hr.add(10);hr.add(11);hr.add(12);
        return hr;
    }

    /**
     * this method return the daily for scheduler daily
     * @return List getDaily
     * */
    private static List getDaily() {
        List<Integer> daily = new ArrayList<>();
        daily.add(1); daily.add(2); daily.add(3);
        daily.add(4); daily.add(5); daily.add(6);
        return daily;
    }

    /**
     * this method return the Week-day for scheduler
     * @return List getWeekly
     * */
    private static List getWeekly() {
        List<Integer> weekly = new ArrayList<>();
        weekly.add(1);  weekly.add(2);  weekly.add(3);
        weekly.add(4);  weekly.add(5);  weekly.add(6);
        weekly.add(7);  weekly.add(8);  weekly.add(9);
        weekly.add(10); weekly.add(11); weekly.add(12);
        weekly.add(13); weekly.add(14); weekly.add(15);
        weekly.add(16); weekly.add(17); weekly.add(18);
        weekly.add(19); weekly.add(20); weekly.add(21);
        weekly.add(22); weekly.add(23); weekly.add(24);
        weekly.add(25); weekly.add(26); weekly.add(27);
        weekly.add(28); weekly.add(29); weekly.add(30);
        weekly.add(31);
        return weekly;
    }

    /**
     * this method return the Monthly-Day for scheduler
     * @return List getMonthly
     * */
    private static List getMonthly() {
        List<Integer> month = new ArrayList<>();
        month.add(1);  month.add(2);  month.add(3);
        month.add(4);  month.add(5);  month.add(6);
        month.add(7);  month.add(8);  month.add(9);
        month.add(10); month.add(11); month.add(12);
        month.add(13); month.add(14); month.add(15);
        month.add(16); month.add(17); month.add(18);
        month.add(19); month.add(20); month.add(21);
        month.add(22); month.add(23); month.add(24);
        month.add(25); month.add(26); month.add(27);
        month.add(28); month.add(29); month.add(30);
        month.add(31);
        return month;
    }

    /**
     * This method use to get the date and time
     * @param date
     * @param startTime
     * @return LocalDateTime
     * */
    public static LocalDateTime getRecurrenceTime(LocalDate date, String startTime) {
        String timeSplit[] = startTime.split(":");
        return date.atStartOfDay().plusHours(Integer.valueOf(timeSplit[0]))
                .plusMinutes(Integer.valueOf(timeSplit[1]));
    }

}