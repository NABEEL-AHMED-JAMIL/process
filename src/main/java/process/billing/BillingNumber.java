package process.billing;

import java.time.YearMonth;
import java.util.regex.Pattern;

/**
 * The numbers billing hands out -- INV-2026-09-0004, CN-2026-09-0001, RCP-2026-09-0012,
 * STM-2905-2026-01-01-2026-12-31 -- and the one check every endpoint that takes a number
 * makes before asking the database: is this shaped like one of ours?
 */
public final class BillingNumber {

    /** Prefix, year, month, running number: what an invoice, credit note or receipt is called. */
    private static final Pattern PERIOD_NUMBER = Pattern.compile("[A-Z]{2,3}-\\d{4}-\\d{2}-\\d{4}");
    /** A statement names the workspace and the range instead. */
    private static final Pattern STATEMENT_NUMBER = Pattern.compile("STM-\\d+-\\d{4}-\\d{2}-\\d{2}-\\d{4}-\\d{2}-\\d{2}");

    private BillingNumber() {}

    /** True for a number this console could have issued; false for anything a URL can carry. */
    public static boolean isValid(String number) {
        return number != null && (PERIOD_NUMBER.matcher(number).matches() || STATEMENT_NUMBER.matcher(number).matches());
    }

    /** "INV-2026-09": the part every number of a kind and a month shares; the running number follows. */
    public static String base(String prefix, YearMonth period) {
        return String.format("%s-%d-%02d", prefix, period.getYear(), period.getMonthValue());
    }

    public static String format(String base, int runningNumber) {
        return String.format("%s-%04d", base, runningNumber);
    }
}
