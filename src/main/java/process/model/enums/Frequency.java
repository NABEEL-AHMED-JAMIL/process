package process.model.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * How a schedule repeats. Cron (Wave 4) steps by the schedule's own cron expression (scheduler.cron_expression, V187;
 * process.util.CronSchedule) rather than by interval_value.
 *
 * @author Nabeel Ahmed
 * */
public enum Frequency {
    Mint, Hr, Daily, Weekly, Monthly, Cron;

    /**
     * ZT-P1: the frequency a caller means -- the stored name in any case, or the console's own label for it ("Hourly",
     * "Minutes", "Every N minutes", "Advanced (cron)"). An API caller that wrote the label it reads on the screen had its
     * "Hourly" stored as it came; the scheduler cannot step by a name it does not know, so the schedule ran its first slot
     * and expired. Empty when the text names no frequency.
     */
    public static Optional<Frequency> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String said = text.trim().toLowerCase(Locale.ROOT);
        for (Frequency frequency : values()) {
            if (frequency.name().toLowerCase(Locale.ROOT).equals(said)) {
                return Optional.of(frequency);
            }
        }
        switch (said) {
            case "minute": case "minutes": case "minutely": case "every n minutes": return Optional.of(Mint);
            case "hour": case "hours": case "hourly": return Optional.of(Hr);
            case "day": case "days": return Optional.of(Daily);
            case "week": case "weeks": return Optional.of(Weekly);
            case "month": case "months": return Optional.of(Monthly);
            case "advanced (cron)": case "cron expression": return Optional.of(Cron);
            default: return Optional.empty();
        }
    }

    /** The words a refusal names the frequencies in. */
    public static final String CHOICES = "Mint (every N minutes), Hr (hourly), Daily, Weekly, Monthly or Cron";
}
