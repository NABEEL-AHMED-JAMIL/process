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

/**
 * @author Nabeel Ahmed
 * */
@Component
public class ProcessTimeUtil {

    private Logger logger = LoggerFactory.getLogger(ProcessTimeUtil.class);

    public static List<String> checked = Arrays.asList("True", "False");

    /**
     * The priorities a job may carry: 1 (highest) to 9.
     *
     * "99" and "100" were concatenated onto the end of that range, and this single list is used
     * for two different things -- it fills the Priority dropdown of the downloadable bulk
     * template, and it is what JobDetailValidation checks an uploaded Priority cell against. So
     * the template offered two values that SourceJobServiceImpl refuses outright on create and on
     * update ("priority must be between 1 (highest) and 9"), and the bulk path, validating against
     * that same over-wide list, accepted them and wrote them to the database. The resulting job
     * then sorted against every other job on a number no other path can produce, and the first
     * attempt to edit it in the console was rejected over a priority the operator never typed.
     */
    public static List<String> priority = IntStream.rangeClosed(1, 9)
         .mapToObj(String::valueOf).collect(Collectors.toList());
    public static List<String> frequency = Arrays.asList("Mint", "Hr", "Daily", "Weekly", "Monthly");

    public static List<String> daysOfWeek = Arrays.asList("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    /**
     * How a stored days_of_week entry is read, in both vocabularies the column actually holds.
     *
     * MON..SUN is the vocabulary: it is what `daysOfWeek` above declares, what the legacy console
     * has always written, and what the jobs list renders. The rewritten console shipped writing
     * '1'..'7' instead, and nothing on the way in normalised or rejected it, so a "Weekly on Mon
     * and Wed" schedule stored "1,3", parsed to an empty set, and quietly became one run a week on
     * whatever weekday the start date happened to fall on -- for ever, with both console screens
     * confirming the days the operator picked. The column therefore holds a mixture of the two
     * today, written by two shipped clients, and no backfill can be trusted to have reached every
     * row of it before the next dispatch tick. Reading both costs seven map entries and repairs
     * every existing row the moment it is next read; the numeric form is ISO-8601, which is where
     * the console's 1=Mon..7=Sun ordering came from in the first place.
     */
    private static final Map<String, DayOfWeek> DAY_CODE_MAP = new LinkedHashMap<>();
    static {
        DAY_CODE_MAP.put("MON", DayOfWeek.MONDAY);
        DAY_CODE_MAP.put("TUE", DayOfWeek.TUESDAY);
        DAY_CODE_MAP.put("WED", DayOfWeek.WEDNESDAY);
        DAY_CODE_MAP.put("THU", DayOfWeek.THURSDAY);
        DAY_CODE_MAP.put("FRI", DayOfWeek.FRIDAY);
        DAY_CODE_MAP.put("SAT", DayOfWeek.SATURDAY);
        DAY_CODE_MAP.put("SUN", DayOfWeek.SUNDAY);
        DAY_CODE_MAP.put("1", DayOfWeek.MONDAY);
        DAY_CODE_MAP.put("2", DayOfWeek.TUESDAY);
        DAY_CODE_MAP.put("3", DayOfWeek.WEDNESDAY);
        DAY_CODE_MAP.put("4", DayOfWeek.THURSDAY);
        DAY_CODE_MAP.put("5", DayOfWeek.FRIDAY);
        DAY_CODE_MAP.put("6", DayOfWeek.SATURDAY);
        DAY_CODE_MAP.put("7", DayOfWeek.SUNDAY);
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
                return candidate -> nextByDaysOfWeek(candidate, interval, scheduler.getDaysOfWeek());
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
        LocalDateTime now = BusinessTime.now();
        // A ring of the last MAX_MISSED_RUNS_REPLAYED slots, so a long gap costs the walk but
        // never the memory: the alternative built the whole list and then threw most of it away.
        Deque<LocalDateTime> recent = new ArrayDeque<>();
        LocalDateTime candidate = step.apply(stepBase(scheduler));
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
        LocalDateTime now = BusinessTime.now();
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

    /**
     * The slot a schedule steps on from: next_run_at, except in one case.
     *
     * next_run_at is an instant since V100 (MIG-163), read back as Chicago wall-clock. A slot that fell in the
     * spring-forward gap -- a Daily job at 02:30 on 8 March, a time that does not exist -- is stored as the moment
     * it can run, 03:30, and reads back as 03:30. Stepping a day on from that moved the schedule to 03:30 for good,
     * which the naive column (holding "02:30") never did. So a daily, weekly or monthly schedule whose slot is its
     * own start time pushed on by a gap steps from the start time instead. Minute and hour schedules step on
     * elapsed wall-clock and are left as they are.
     */
    private static LocalDateTime stepBase(Scheduler scheduler) {
        LocalDateTime slot = scheduler.getNextRunAt();
        String frequency = scheduler.getFrequency();
        boolean daysOrLonger = Frequency.Daily.name().equals(frequency) || Frequency.Weekly.name().equals(frequency)
            || Frequency.Monthly.name().equals(frequency);
        if (slot == null || scheduler.getStartTime() == null || !daysOrLonger) {
            return slot;
        }
        LocalDateTime intended = slot.toLocalDate().atTime(scheduler.getStartTime());
        if (!intended.equals(slot) && BusinessTime.ZONE.getRules().getValidOffsets(intended).isEmpty()
            && BusinessTime.instantOf(intended).equals(BusinessTime.instantOf(slot))) {
            return intended;
        }
        return slot;
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

        LocalDateTime now = BusinessTime.now();
        LocalDateTime next = step.apply(stepBase(scheduler));
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

    /**
     * The next named day, honouring "Repeat every N weeks".
     *
     * intervalValue was not passed in at all, so any weekly schedule that named days stepped to
     * the next named day inside seven days and the repeat count was decoration: "every 2 weeks on
     * Mon, Wed" ran every Mon and Wed, twice the cadence that was asked for. The editor offers the
     * repeat count and the day pickers side by side, so that is the combination it encourages.
     * The two settings answer different questions and both have to be obeyed -- the days say which
     * days inside a running week fire, the interval says which weeks run at all -- so once the
     * last named day of a week has gone by the walk jumps whole weeks to the first named day of
     * the week N weeks on.
     *
     * This changes the cadence of every stored weekly schedule that names days AND carries an
     * interval above 1: they have been firing every week and will now fire every N. Those rows
     * are being brought back to what their own editor screen has been showing all along rather
     * than migrated -- nothing is rewritten, next_run_at still names a real slot, and the first
     * run after this ships is the one already scheduled. Only the step after it widens. Rows with
     * an interval of 1, which is what the console defaults to, keep the timetable they have.
     */
    private static LocalDateTime nextByDaysOfWeek(LocalDateTime from, long intervalWeeks, String daysOfWeekCsv) {
        long weeks = Math.max(intervalWeeks, 1L);
        Set<DayOfWeek> selected = parseDaysOfWeek(daysOfWeekCsv);
        if (selected.isEmpty()) {
            // An unreadable day list is no day constraint at all, so the plain weekly step stands.
            return from.plusWeeks(weeks);
        }
        // Days still ahead in the week that is already running come first: they belong to a week
        // the interval has already let through, whatever the interval is.
        LocalDateTime candidate = from.plusDays(1);
        while (candidate.getDayOfWeek().getValue() > from.getDayOfWeek().getValue()) {
            if (selected.contains(candidate.getDayOfWeek())) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        // This week is spent, so skip to the first named day of the week `weeks` on. Measured from
        // the Monday of the target week rather than by stepping seven days from `from`: the latter
        // lands on `from`'s own weekday and then has to hunt forward, which walks the cadence
        // later by up to six days on every cycle instead of holding it.
        LocalDateTime targetWeekStart = from.plusWeeks(weeks).minusDays(from.getDayOfWeek().getValue() - 1L);
        for (int i = 0; i < 7; i++) {
            LocalDateTime day = targetWeekStart.plusDays(i);
            if (selected.contains(day.getDayOfWeek())) {
                return day;
            }
        }
        return from.plusWeeks(weeks);
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
