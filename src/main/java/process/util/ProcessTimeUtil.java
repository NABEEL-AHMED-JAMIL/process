package process.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.model.enums.Frequency;
import process.model.pojo.Scheduler;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Component
public class ProcessTimeUtil {

    private Logger logger = LoggerFactory.getLogger(ProcessTimeUtil.class);

    public static List<String> checked = Arrays.asList("True", "False");
    public static List<String> priority = Stream.concat(IntStream.rangeClosed(1, 9)
         .mapToObj(String::valueOf), Stream.of("99", "100")).collect(Collectors.toList());
    public static List<String> frequency = Arrays.asList("Mint", "Hr", "Daily", "Weekly", "Monthly");

    public static List<String> daysOfWeek = Arrays.asList("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private static final Map<String, DayOfWeek> DAY_CODE_MAP = new LinkedHashMap<>();
    static {
        DAY_CODE_MAP.put("MON", DayOfWeek.MONDAY);
        DAY_CODE_MAP.put("TUE", DayOfWeek.TUESDAY);
        DAY_CODE_MAP.put("WED", DayOfWeek.WEDNESDAY);
        DAY_CODE_MAP.put("THU", DayOfWeek.THURSDAY);
        DAY_CODE_MAP.put("FRI", DayOfWeek.FRIDAY);
        DAY_CODE_MAP.put("SAT", DayOfWeek.SATURDAY);
        DAY_CODE_MAP.put("SUN", DayOfWeek.SUNDAY);
    }

    public static Map<String, List<?>> frequencyDetail = new HashMap<>();

    static {
        frequencyDetail.put("Mint", getMints());
        frequencyDetail.put("Hr", getHr());
        frequencyDetail.put("Daily", getDaily());
        frequencyDetail.put("Weekly", getWeekly());
        frequencyDetail.put("Monthly", getMonthly());
    }

    private static List getMints() {
        return Arrays.asList(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55);
    }

    private static List getHr() {
        return Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    }

    private static List getDaily() {
        return Arrays.asList(1, 2, 3, 4, 5, 6);
    }

    private static List getWeekly() {
        return Arrays.asList(1, 2, 3, 4);
    }

    private static List getMonthly() {
        return Arrays.asList(1, 2, 3, 4, 5, 6);
    }

    public static LocalDateTime getRecurrenceTime(LocalDate date, String startTime) {
        String[] timeSplit = startTime.split(":");
        return date.atStartOfDay().plusHours(Integer.parseInt(timeSplit[0])).plusMinutes(Integer.parseInt(timeSplit[1]));
    }

    private static UnaryOperator<LocalDateTime> stepFunction(Scheduler scheduler) {
        long interval = Long.parseLong(scheduler.getIntervalValue());
        if (scheduler.getFrequency().equals(Frequency.Mint.name())) {
            return candidate -> candidate.plusMinutes(interval);
        } else if (scheduler.getFrequency().equals(Frequency.Hr.name())) {
            return candidate -> candidate.plusHours(interval);
        } else if (scheduler.getFrequency().equals(Frequency.Daily.name())) {
            return candidate -> candidate.plusDays(interval);
        } else if (scheduler.getFrequency().equals(Frequency.Weekly.name())) {
            if (!ProcessUtil.isNull(scheduler.getDaysOfWeek())) {
                return candidate -> nextByDaysOfWeek(candidate, scheduler.getDaysOfWeek());
            }
            return candidate -> candidate.plusWeeks(interval);
        } else if (scheduler.getFrequency().equals(Frequency.Monthly.name())) {
            if (scheduler.getDayOfMonth() != null) {
                return candidate -> nextByDayOfMonth(candidate, interval, scheduler.getDayOfMonth());
            }
            return candidate -> candidate.plusMonths(interval);
        }
        return null;
    }

    public static List<LocalDateTime> computeMissedRuns(Scheduler scheduler) {
        if (ProcessUtil.isNull(scheduler.getIntervalValue()) || scheduler.getNextRunAt() == null) {
            return Collections.emptyList();
        }
        UnaryOperator<LocalDateTime> step = stepFunction(scheduler);
        if (step == null) {
            return Collections.emptyList();
        }
        LocalDateTime now = LocalDateTime.now();
        List<LocalDateTime> missed = new ArrayList<>();
        LocalDateTime candidate = step.apply(scheduler.getNextRunAt());
        int guard = 0;
        while (!candidate.isAfter(now) && guard++ < 100000) {
            missed.add(candidate);
            candidate = step.apply(candidate);
        }
        return missed;
    }

    public static LocalDateTime resolveInitialNextRun(Scheduler scheduler) {
        LocalDateTime seed = getRecurrenceTime(scheduler.getStartDate(), scheduler.getStartTime().toString());
        if (ProcessUtil.isNull(scheduler.getIntervalValue())) {
            return seed;
        }
        UnaryOperator<LocalDateTime> step = stepFunction(scheduler);
        if (step == null) {
            return seed;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime candidate = seed;
        int guard = 0;
        while (!candidate.isAfter(now) && guard++ < 100000) {
            candidate = step.apply(candidate);
        }
        return candidate;
    }

    public static void applyInitialSchedule(Scheduler scheduler) {
        LocalDateTime seed = resolveInitialNextRun(scheduler);
        scheduler.setNextRunAt(seed);
        boolean alreadyPastEnd = !ProcessUtil.isNull(scheduler.getEndDate()) && seed.toLocalDate().isAfter(scheduler.getEndDate());
        scheduler.setExpired(alreadyPastEnd);
    }

    public static LocalDateTime computeNextRun(Scheduler scheduler) {
        if (ProcessUtil.isNull(scheduler.getIntervalValue()) || scheduler.getNextRunAt() == null) {
            return null;
        }
        UnaryOperator<LocalDateTime> step = stepFunction(scheduler);
        if (step == null) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = step.apply(scheduler.getNextRunAt());
        int guard = 0;
        while (!next.isAfter(now) && guard++ < 100000) {
            next = step.apply(next);
        }
        return next;
    }

    private static LocalDateTime nextByDaysOfWeek(LocalDateTime from, String daysOfWeekCsv) {
        Set<DayOfWeek> selected = Arrays.stream(daysOfWeekCsv.split(","))
            .map(String::trim)
            .filter(code -> !code.isEmpty())
            .map(code -> DAY_CODE_MAP.get(code.toUpperCase()))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (selected.isEmpty()) {
            return from.plusWeeks(1);
        }
        LocalDateTime candidate = from.plusDays(1);
        for (int i = 0; i < 7; i++) {
            if (selected.contains(candidate.getDayOfWeek())) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return from.plusWeeks(1);
    }

    private static LocalDateTime nextByDayOfMonth(LocalDateTime from, long intervalMonths, int dayOfMonth) {
        LocalDateTime base = from.plusMonths(Math.max(intervalMonths, 1));
        int lastDayOfTargetMonth = base.toLocalDate().lengthOfMonth();
        int resolvedDay = (dayOfMonth <= 0) ? lastDayOfTargetMonth : Math.min(dayOfMonth, lastDayOfTargetMonth);
        return base.withDayOfMonth(resolvedDay);
    }

    public static void applyNextRun(Scheduler scheduler) {
        LocalDateTime nextJobRun = computeNextRun(scheduler);
        if (nextJobRun == null) {
            scheduler.setExpired(true);
            return;
        }

        if (!ProcessUtil.isNull(scheduler.getEndDate())) {
            if (!nextJobRun.toLocalDate().isAfter(scheduler.getEndDate())) {
                scheduler.setNextRunAt(nextJobRun);
                return;
            }
            scheduler.setExpired(true);
            return;
        }
        scheduler.setNextRunAt(nextJobRun);
    }

    public static boolean isLastFlight(Scheduler scheduler) {
        if (ProcessUtil.isNull(scheduler.getEndDate()) || scheduler.getNextRunAt() == null) {
            return false;
        }
        LocalDateTime nextAfterThis = computeNextRun(scheduler);
        if (nextAfterThis == null) {
            return true;
        }
        return nextAfterThis.toLocalDate().isAfter(scheduler.getEndDate());
    }

}
