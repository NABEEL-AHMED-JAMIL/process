package process.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author Nabeel Ahmed
 * This class use to handle the all time realted procss
 */
@Component
public class ProcessTimeUtil {

    private Logger logger = LoggerFactory.getLogger(ProcessTimeUtil.class);

    public static List<String> frequency = new ArrayList<>();
    public static Set<String> triggerDetail = new HashSet<>();
    public static Map<String, List<?>> frequencyDetail = new HashMap<>();

    static {
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
        // --------------------- //
        triggerDetail.add("process.engine.task.HelloWorldTask");
        triggerDetail.add("process.engine.task.StockPriceReportTask");
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
        daily.add(1);
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
        List<Integer> days = new ArrayList<>();
        days.add(1);  days.add(2);  days.add(3);
        days.add(4);  days.add(5);  days.add(6);
        days.add(7);  days.add(8);  days.add(9);
        days.add(10); days.add(11); days.add(12);
        days.add(13); days.add(14); days.add(15);
        days.add(16); days.add(17); days.add(18);
        days.add(19); days.add(20); days.add(21);
        days.add(22); days.add(23); days.add(24);
        days.add(25); days.add(26); days.add(27);
        days.add(28); days.add(29); days.add(30);
        days.add(31);
        return days;
    }

    /**
     * This method use to get the date and time
     * @param date
     * @param startTime
     * @return LocalDateTime
     * */
    public static LocalDateTime getRecurrenceTime(LocalDate date, String startTime) {
        String timeSplit[] = startTime.split(":");
        return date.atStartOfDay().plusHours(Integer.valueOf(timeSplit[0])).plusMinutes(Integer.valueOf(timeSplit[1]));
    }

}