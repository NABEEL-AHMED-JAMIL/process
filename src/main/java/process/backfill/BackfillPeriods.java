package process.backfill;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * MIG-347: the periods a backfill runs, one run each, in the workspace's billing zone -- a day (midnight to midnight there,
 * so 23 hours on the spring-forward day and 25 on the fall-back day), an hour (by the clock: the fall-back day has two
 * 01:00 hours, each its own period), or a week (seven days from each start date). Pure: the zone and the dates are given.
 */
public final class BackfillPeriods {

    /** The most periods one backfill has: a year of days. */
    public static final int MAX_PERIODS = 366;

    public enum Step {
        DAILY, HOURLY, WEEKLY;

        public static Optional<Step> of(String word) {
            if (word == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(Step.valueOf(word.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
        }

        public String word() {
            return this.name().toLowerCase(Locale.ROOT);
        }
    }

    /** One period: [start, end), the day it is for in the zone. */
    public static final class Period {
        public final Instant start;
        public final Instant end;
        public final LocalDate date;

        Period(Instant start, Instant end, LocalDate date) {
            this.start = start;
            this.end = end;
            this.date = date;
        }

        @Override
        public String toString() {
            return this.date + " [" + this.start + ", " + this.end + ")";
        }
    }

    private BackfillPeriods() {
    }

    /**
     * Every period of [from, to] (both days included) by {@code step} in {@code zone}, oldest first, up to the first that
     * starts after {@code notAfter} (a range is in the past: an hour that has not begun yet is not a period). An
     * IllegalArgumentException says why the range cannot be one backfill: it ends before it starts, or holds more than
     * {@value #MAX_PERIODS} periods.
     */
    public static List<Period> of(LocalDate from, LocalDate to, Step step, ZoneId zone, Instant notAfter) {
        if (from == null || to == null || step == null || zone == null) {
            throw new IllegalArgumentException("A backfill needs a first and a last day and a step.");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The range ends before it starts.");
        }
        // Counted before any is made, so a ten-year hourly range costs nothing to refuse.
        long days = Duration.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()).toDays();
        long estimate = step == Step.HOURLY ? days * 24 : step == Step.WEEKLY ? (days + 6) / 7 : days;
        if (estimate > MAX_PERIODS + 2) {
            throw tooMany(estimate, step);
        }
        List<Period> out = new ArrayList<>();
        switch (step) {
            case DAILY:
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    add(out, new Period(d.atStartOfDay(zone).toInstant(), d.plusDays(1).atStartOfDay(zone).toInstant(), d), notAfter);
                }
                break;
            case WEEKLY:
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(7)) {
                    add(out, new Period(d.atStartOfDay(zone).toInstant(), d.plusDays(7).atStartOfDay(zone).toInstant(), d), notAfter);
                }
                break;
            case HOURLY:
            default:
                Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
                for (Instant t = from.atStartOfDay(zone).toInstant(); t.isBefore(end); t = t.plus(Duration.ofHours(1))) {
                    add(out, new Period(t, t.plus(Duration.ofHours(1)), LocalDate.ofInstant(t, zone)), notAfter);
                }
                break;
        }
        if (out.size() > MAX_PERIODS) {
            throw tooMany(out.size(), step);
        }
        return Collections.unmodifiableList(out);
    }

    private static void add(List<Period> out, Period period, Instant notAfter) {
        if (notAfter == null || !period.start.isAfter(notAfter)) {
            out.add(period);
        }
    }

    private static IllegalArgumentException tooMany(long count, Step step) {
        return new IllegalArgumentException(String.format("That is %d %s periods; a backfill has at most %d. Pick a shorter range%s.", count,
            step.word(), MAX_PERIODS, step == Step.WEEKLY ? "" : " or a longer step"));
    }
}
