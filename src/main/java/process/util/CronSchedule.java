package process.util;

import org.springframework.scheduling.support.CronSequenceGenerator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.TimeZone;

/**
 * The Cron frequency (Wave 4): a schedule's own cron expression, checked when it is saved and stepped by the scheduler.
 *
 * <b>The dialect.</b> Unix cron's five fields -- minute hour day-of-month month day-of-week -- which is what the console
 * writes and what people know; or Spring's six, whose leading seconds field must then be exactly 0. The five-field form
 * is read as the six-field one with "0 " in front. Either way a schedule fires at most once a minute: a seconds field
 * other than 0 is refused, not rounded, since the scheduler ticks by the minute and a seconds-level expression would
 * promise runs it never makes. Names (MON-FRI, JAN), ranges, lists and steps are Spring's; Sunday is 0 or 7. Quartz's
 * L, W, # and year field are not.
 *
 * Spring 5.2's CronSequenceGenerator does the reading and the stepping (the build is on Spring 5.2, which has no
 * CronExpression; the dialect is the same bar L/W/#). It is handed a zone with no daylight saving, so what it steps is
 * the Chicago wall-clock reading itself -- the domain every other frequency steps in (ProcessTimeUtil). What a
 * wall-clock slot means as an instant, across the gap and the repeated hour, is BusinessTime's, decided once for every
 * schedule: a slot in the spring-forward gap (02:30 on the second Sunday of March) runs the gap's length on, at 03:30
 * CDT; a slot in the repeated hour (01:30 on the first Sunday of November) runs once, when the wall clock first reads
 * it.
 */
public final class CronSchedule {

    /** The column's width (V187): scheduler.cron_expression varchar(120). */
    public static final int MAX_LENGTH = 120;

    /** The generator gives up after a year with no matching day; a leap-day schedule may be up to eight years off. */
    private static final int YEARS_SEARCHED = 8;

    private static final TimeZone WALL_CLOCK = TimeZone.getTimeZone("UTC");

    private static final String FIELDS = "minute hour day-of-month month day-of-week";

    private CronSchedule() {
    }

    /** The expression as it is stored: trimmed, with single spaces between its fields; null when there is nothing. */
    public static String normalise(String expression) {
        if (expression == null || expression.trim().isEmpty()) {
            return null;
        }
        return expression.trim().replaceAll("\\s+", " ");
    }

    /**
     * What is wrong with this expression, as a sentence for the caller; null when a schedule may run on it.
     */
    // CronSequenceGenerator: CronExpression differs around DST and day-of-week; switching is a behaviour change for Wave 6, with its own tests.
    @SuppressWarnings("deprecation")
    public static String problem(String expression) {
        String tidy = normalise(expression);
        if (tidy == null) {
            return String.format("A Cron schedule needs a cron expression: five fields, %s — e.g. '0 9 * * MON-FRI' for "
                + "09:00 on weekdays, Chicago time.", FIELDS);
        }
        if (tidy.length() > MAX_LENGTH) {
            return String.format("A cron expression is at most %d characters; this one has %d.", MAX_LENGTH, tidy.length());
        }
        String[] fields = tidy.split(" ");
        if (fields.length == 6 && !fields[0].matches("0+")) {
            return String.format("Schedules run at most once a minute, so a six-field cron expression's seconds field must be 0 "
                + "(or leave it out and write the five fields, %s); got '%s'.", FIELDS, tidy);
        }
        if (fields.length != 5 && fields.length != 6) {
            return String.format("A cron expression has 5 fields (%s), or 6 with a leading seconds field of 0; got %d in '%s'.",
                FIELDS, fields.length, tidy);
        }
        CronSequenceGenerator generator;
        try {
            generator = new CronSequenceGenerator(springForm(tidy), WALL_CLOCK);
        } catch (RuntimeException ex) {
            return String.format("'%s' is not a cron expression this scheduler reads (%s): %s.", tidy, FIELDS, reasonOf(ex));
        }
        if (next(generator, BusinessTime.now()) == null) {
            return String.format("'%s' never fires: no date matches its day-of-month, month and day-of-week.", tidy);
        }
        return null;
    }

    /** The six-field form the generator reads: the five-field form with seconds 0 in front. Null for nothing. */
    static String springForm(String expression) {
        String tidy = normalise(expression);
        if (tidy == null) {
            return null;
        }
        return tidy.split(" ").length == 5 ? "0 " + tidy : tidy;
    }

    /**
     * The first slot strictly after this Chicago wall-clock reading; null when the expression cannot be read or never
     * fires. Only an expression {@link #problem} passed is ever stored, so null here means a row written some other way.
     */
    // CronSequenceGenerator: CronExpression differs around DST and day-of-week; switching is a behaviour change for Wave 6, with its own tests.
    @SuppressWarnings("deprecation")
    public static LocalDateTime next(String expression, LocalDateTime after) {
        if (after == null || problemOfShape(expression)) {
            return null;
        }
        try {
            return next(new CronSequenceGenerator(springForm(expression), WALL_CLOCK), after);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** The shape checks alone -- what the generator itself does not refuse (a seconds field, a seventh field). */
    private static boolean problemOfShape(String expression) {
        String tidy = normalise(expression);
        if (tidy == null) {
            return true;
        }
        String[] fields = tidy.split(" ");
        return !(fields.length == 5 || (fields.length == 6 && fields[0].matches("0+")));
    }

    // CronSequenceGenerator: CronExpression differs around DST and day-of-week; switching is a behaviour change for Wave 6, with its own tests.
    @SuppressWarnings("deprecation")
    private static LocalDateTime next(CronSequenceGenerator generator, LocalDateTime after) {
        // Whole seconds: the generator drops the millis, and a reading at 09:00:00.5 must still step past 09:00.
        LocalDateTime from = after.withNano(0);
        for (int year = 0; year < YEARS_SEARCHED; year++) {
            try {
                Date found = generator.next(Date.from(from.toInstant(ZoneOffset.UTC)));
                return LocalDateTime.ofInstant(found.toInstant(), ZoneOffset.UTC);
            } catch (IllegalArgumentException noDayWithinAYear) {
                // No matching day in the 366 days searched: nothing is skipped by starting again a year on.
                from = from.plus(Duration.ofDays(365));
            }
        }
        return null;
    }

    private static String reasonOf(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
