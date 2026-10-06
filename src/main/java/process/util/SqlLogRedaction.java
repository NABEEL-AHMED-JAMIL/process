package process.util;

import java.util.regex.Pattern;

/**
 * A string-built query as it may be logged: its shape, with every literal replaced by '?'.
 *
 * QueryService builds every query by concatenation, so the composed text carries the caller's tenant id,
 * job and run ids, dates and whatever they typed into a search box (MIG-74, DEF-142). The shape -- the
 * joins, the predicates, which branch of the builder ran -- is what a developer reads the log for; the
 * values are what must not reach a shared log index. Quoted strings go first, with SQL's doubled-quote
 * escape, so a quote inside a search term cannot end the literal early and leak the rest of it; then
 * bare numbers that stand alone, which leaves identifiers such as job_queue_id or v2 alone.
 *
 * A candidate for platform-commons, so the next service that string-builds a query logs it the same way.
 */
public final class SqlLogRedaction {

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");

    /** A quote left unpaired: everything after it could be the rest of a value, so none of it is kept. */
    private static final Pattern UNPAIRED_QUOTE = Pattern.compile("'.*", Pattern.DOTALL);

    private static final Pattern NUMBER_LITERAL = Pattern.compile("(?<![\\w.$])-?\\d+(?:\\.\\d+)?(?![\\w.])");

    private SqlLogRedaction() {
    }

    public static String redact(String sql) {
        if (sql == null) {
            return null;
        }
        String withoutStrings = UNPAIRED_QUOTE.matcher(STRING_LITERAL.matcher(sql).replaceAll("?")).replaceAll("?");
        return NUMBER_LITERAL.matcher(withoutStrings).replaceAll("?");
    }
}
