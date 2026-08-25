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

    /**
     * How many slots a catch-up will write down before it stops counting.
     *
     * Every missed run becomes a queue row, an audit log line and a notification, all inside the
     * scheduler tick that noticed. Uncapped, a five-minute job that was off for a fortnight came
     * back with four thousand of them in one pass, holding a lock whose timeout is ten minutes.
     * Fifty is enough to see that something was missed and roughly how much.
     */
    public static final int MAX_MISSED_RUNS_REPLAYED = 50;

    /**
     * The slots that went by unattended, oldest first, capped.
     *
     * When the cap bites, the most recent slots are the ones kept: after a long outage, what
     * happened in the last hour is worth more than what happened on the first morning.
     */
    public static List<LocalDateTime> computeMissedRuns(Scheduler scheduler) {
        if (ProcessUtil.isNull(scheduler.getIntervalValue()) || scheduler.getNextRunAt() == null) {
            return Collections.emptyList();
        }
        UnaryOperator<LocalDateTime> step = stepFunction(scheduler);
        if (step == null) {
            return Collections.emptyList();
        }
        LocalDateTime now = LocalDateTime.now();
        // A ring of the last MAX_MISSED_RUNS_REPLAYED slots, so a long gap costs the walk but
        // never the memory: the alternative built the whole list and then threw most of it away.
        Deque<LocalDateTime> recent = new ArrayDeque<>();
        LocalDateTime candidate = step.apply(scheduler.getNextRunAt());
        int guard = 0;
        while (!candidate.isAfter(now) && guard++ < 100000) {
            if (recent.size() == MAX_MISSED_RUNS_REPLAYED) {
                recent.removeFirst();
            }
            recent.addLast(candidate);
            candidate = step.apply(candidate);
        }
        return new ArrayList<>(recent);
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
        // The start date says when a schedule begins, not which days it runs on. A seed already in
        // the future was being accepted as-is, so a Mon/Thu schedule created on a Tuesday afternoon
        // took its first run that Tuesday, and a "day 1 of the month" schedule took its first run
        // on whatever date it happened to be created.
        if (hasDayRule(scheduler)) {
            // Walk a day at a time rather than stepping: the step for these frequencies jumps a
            // whole week or month, which would skip a valid slot still ahead in the current one.
            // Two years of days is far past any monthly or weekly rule that can be satisfied.
            while ((!candidate.isAfter(now) || !matchesDayRule(scheduler, candidate)) && guard++ < 732) {
                candidate = candidate.plusDays(1);
            }
            return candidate;
        }
        while (!candidate.isAfter(now) && guard++ < 100000) {
            candidate = step.apply(candidate);
        }
        return candidate;
    }

    /** Whether this schedule names particular days, rather than just an interval. */
    private static boolean hasDayRule(Scheduler scheduler) {
        return (Frequency.Weekly.name().equals(scheduler.getFrequency())
                   && !ProcessUtil.isNull(scheduler.getDaysOfWeek())
                   && !parseDaysOfWeek(scheduler.getDaysOfWeek()).isEmpty())
            || (Frequency.Monthly.name().equals(scheduler.getFrequency())
                   && scheduler.getDayOfMonth() != null);
    }

    /**
     * Whether a candidate falls on a day this schedule names. Frequencies that carry no day rule
     * are unconstrained, so they always match.
     */
    private static boolean matchesDayRule(Scheduler scheduler, LocalDateTime candidate) {
        if (Frequency.Weekly.name().equals(scheduler.getFrequency())
            && !ProcessUtil.isNull(scheduler.getDaysOfWeek())) {
            Set<DayOfWeek> selected = parseDaysOfWeek(scheduler.getDaysOfWeek());
            // An unreadable list is no constraint at all; nextByDaysOfWeek falls back the same way.
            return selected.isEmpty() || selected.contains(candidate.getDayOfWeek());
        }
        if (Frequency.Monthly.name().equals(scheduler.getFrequency())
            && scheduler.getDayOfMonth() != null) {
            // Compare against the day the rule resolves to in *this* month, not the raw setting:
            // the 31st resolves to the 30th in September, and 0 means the last day.
            int lastDay = candidate.toLocalDate().lengthOfMonth();
            int wanted = scheduler.getDayOfMonth() <= 0
                ? lastDay : Math.min(scheduler.getDayOfMonth(), lastDay);
            return candidate.getDayOfMonth() == wanted;
        }
        return true;
    }

    private static Set<DayOfWeek> parseDaysOfWeek(String daysOfWeekCsv) {
        return Arrays.stream(daysOfWeekCsv.split(","))
            .map(String::trim)
            .filter(code -> !code.isEmpty())
            .map(code -> DAY_CODE_MAP.get(code.toUpperCase()))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
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
        if (!next.isAfter(now)) {
            // The guard ran out before the walk caught up -- a five-minute schedule left alone
            // for a year needs more than a hundred thousand steps. Returning what the walk had
            // reached would put next_run_at in the past, so the job is due the moment it is
            // written and due again on the next tick, for ever. Step forward from now instead:
            // the schedule is resumed rather than left permanently overdue.
            LocalDateTime fromNow = now;
            int forward = 0;
            while (!fromNow.isAfter(now) && forward++ < 1000) {
                fromNow = step.apply(fromNow);
            }
            return fromNow.isAfter(now) ? fromNow : null;
        }
        return next;
    }

    private static LocalDateTime nextByDaysOfWeek(LocalDateTime from, String daysOfWeekCsv) {
        Set<DayOfWeek> selected = parseDaysOfWeek(daysOfWeekCsv);
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
