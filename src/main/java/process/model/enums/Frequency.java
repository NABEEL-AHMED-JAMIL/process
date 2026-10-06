package process.model.enums;

/**
 * How a schedule repeats. Cron (Wave 4) steps by the schedule's own cron expression (scheduler.cron_expression, V187;
 * process.util.CronSchedule) rather than by interval_value.
 *
 * @author Nabeel Ahmed
 * */
public enum Frequency {
    Mint, Hr, Daily, Weekly, Monthly, Cron
}
