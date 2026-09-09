package process.analytics.canvas;

import process.analytics.AnalyticsEngine;
import process.analytics.AnalyticsException;
import process.analytics.dto.ColumnDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a filter tree into a WHERE clause that contains no value a user sent.
 *
 * <b>07's rule is one sentence with two halves, and they need different mechanisms.</b> "Do not
 * construct raw SQL by string concatenation with untrusted values" is satisfied for VALUES by
 * binding them: every operand below becomes a "?" in the text and an entry in a parameter list, so
 * a quote, a semicolon, a comment, a UNION or a whole nested SELECT typed into a filter box is a
 * string being compared against a column and cannot be anything else. There is no escaping function
 * anywhere in this class, deliberately -- an escaper is a thing that can be got wrong, and a bound
 * parameter is a thing that cannot.
 *
 * <b>The other half is field names, and SQL has no parameter for an identifier.</b> A name must be
 * written into the text, so the question is only where the text comes from. It comes from
 * {@link Columns}: the dataset's own schema, read by a DESCRIBE inside the same session the query
 * will run in, held as an allow-list, and -- the part that matters -- the string emitted is the
 * SCHEMA's spelling of the name and never the request's. A caller asking for "Region" on a file
 * whose column is "region" gets "region" written into the SQL; a caller asking for anything the
 * DESCRIBE did not return gets a refusal. That is an allow-list derived from the data itself, which
 * is a different and much stronger thing than a pattern hoping to recognise the dangerous shapes.
 *
 * <b>Why the quoting is still done, given the allow-list.</b> Because a column name is not an
 * identifier: a CSV header can say "order date", "sum(x)" or "a""b", and DuckDB will happily read a
 * file with any of those in it. Quoting with doubled quotes is what makes such a name usable rather
 * than what makes it safe -- the safety is that it came from the schema. Both are done, and neither
 * is asked to do the other's job.
 *
 * <b>And every value is typed to its column before it is bound.</b> Measured on duckdb_jdbc 1.1.3,
 * binding the String "20" against a DOUBLE column is not a comparison, it is
 * "Binder Error: Cannot compare values of type DOUBLE and type VARCHAR" -- so an untyped bind would
 * have turned every numeric filter into an engine error. Coercing here means a threshold that is not
 * a number is refused with a sentence a person can act on, before a session is opened.
 *
 * <b>Assume this file has phase three's hole until it has been attacked.</b> The statement gate
 * checked table_name and not schema_name, and a quoted schema carried an s3:// URL into a signed
 * cross-bucket GET. The equivalent here would be a field name that is really an expression or a
 * location; FilterCompilerTest tries it, and {@link process.analytics.StatementGate#confirmComposed}
 * stands behind this class on the composed statement as the second layer, exactly as the two path
 * checks in DuckDbAnalyticsEngine.writableUrl deliberately overlap.
 *
 * @author Nabeel Ahmed
 */
public final class FilterCompiler {

    /**
     * How deeply filter groups may nest.
     *
     * The compilation below is recursive and the tree comes from a request body, so this is the
     * bound that keeps a deliberately deep payload from being a StackOverflowError -- an Error
     * rather than an Exception, which would sail past every catch the endpoint has. Far past any
     * filter a person builds: eight levels is "A and (B or (C and (D or ...)))" nested eight times.
     */
    private static final int MAX_DEPTH = 8;

    /**
     * How many conditions one analysis may filter by, across the whole tree.
     *
     * Each one is a predicate DuckDB evaluates per row, so a filter with ten thousand conditions in
     * it is a denial of service written in JSON rather than a question. It is also far past the
     * point where the SQL text stops being something an operator can read in a log.
     */
    private static final int MAX_CLAUSES = 200;

    /**
     * How many operands an IN or NOT_IN may carry.
     *
     * Each one is a bound parameter, and a list this long is already a join wearing a disguise.
     * Generous enough for a multi-select over a real dimension, small enough that the parameter
     * list cannot be the reason a request is expensive.
     */
    private static final int MAX_IN_VALUES = 500;

    /**
     * How many characters of a rejected field name are quoted back to the caller.
     *
     * The name is echoed because a filter over the wrong column is otherwise impossible to debug
     * from the message alone -- but it is the caller's own string coming back out, so it is capped
     * and stripped first. A message is rendered into a page by somebody else's code, and a module
     * that hands back an arbitrary sixty-kilobyte string from a request body has made that
     * somebody's escaping bug into its own.
     */
    private static final int NAME_ECHO_LIMIT = 64;

    /** The relative windows a RELATIVE_DATE may name. Closed, because each one is a decision. */
    /** A time carrying an offset, which LocalTime cannot parse but a TIMETZ column holds. */
    private static final java.util.regex.Pattern OFFSET_TIME = java.util.regex.Pattern.compile(
        "\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?\\s*[+-]\\d{2}(:?\\d{2})?");

    private static final String LAST_N_DAYS = "LAST_N_DAYS:";

    private final Columns columns;

    /**
     * The instant every relative window is measured from.
     *
     * <b>A field rather than a call to now(), and the clock is the SERVER's.</b> 07 asks for
     * relative dates and does not say which clock, and the audit records that as an open decision:
     * the browser's zone, the server's, or the dataset's own values. It is the server's, resolved
     * ONCE per analysis and then bound as two absolute instants -- which settles three things at
     * once. Every clause in one request sees the same "now", so "last 7 days" and "last 30 days"
     * in the same filter cannot disagree about where today ends. The SQL contains no current_date,
     * so the same statement run twice returns the same rows and can be reasoned about after the
     * fact. And the resolved window is reported back on the response, so a screen can show the
     * dates it actually used rather than the word the user picked.
     */
    private final LocalDate today;

    /** Every relative window this compilation resolved, so the response can say what it used. */
    private final Map<String, String> resolvedWindows = new LinkedHashMap<>();

    private int clauseCount;

    public FilterCompiler(Columns columns) {
        this(columns, LocalDate.now(ZoneId.systemDefault()));
    }

    /** The same compiler with the clock named, so a test does not have to wait for tomorrow. */
    public FilterCompiler(Columns columns, LocalDate today) {
        this.columns = columns;
        this.today = today;
    }

    /**
     * What a relative window resolved to, keyed by the token that asked for it.
     *
     * Populated as a side effect of compiling, which is the honest shape: the resolution happens
     * once, where the clock is, and reporting it from anywhere else would mean resolving it twice.
     */
    public Map<String, String> getResolvedWindows() {
        return this.resolvedWindows;
    }

    /**
     * The whole tree as one predicate, or null when there is nothing to filter by.
     *
     * Null rather than "1=1", so the caller emits no WHERE at all. A tautology in the text is a
     * thing a reader has to decide is harmless every time they read the SQL.
     */
    public AnalyticsEngine.BoundStatement compile(FilterClause root) throws AnalyticsException {
        if (root == null) {
            return null;
        }
        List<Object> parameters = new ArrayList<>();
        String sql = this.node(root, parameters, 0);
        if (sql == null) {
            return null;
        }
        return new AnalyticsEngine.BoundStatement(sql, parameters);
    }

    /**
     * One node: a group joined by its operator, or a single condition.
     *
     * Recursive, and the depth is carried rather than counted, so a group nested inside a group
     * inside a group is refused at the level that broke the rule rather than after the whole tree
     * has been walked.
     */
    private String node(FilterClause clause, List<Object> parameters, int depth)
        throws AnalyticsException {

        if (depth > MAX_DEPTH) {
            throw new AnalyticsException("That filter is nested too deeply. Flatten some of the "
                + "groups and try again.");
        }
        if (clause.isGroup()) {
            return this.group(clause, parameters, depth);
        }
        if (++this.clauseCount > MAX_CLAUSES) {
            throw new AnalyticsException("An analysis may have up to " + MAX_CLAUSES
                + " filter conditions.");
        }
        return this.condition(clause, parameters);
    }

    private String group(FilterClause clause, List<Object> parameters, int depth)
        throws AnalyticsException {

        if (clause.getOp() == null) {
            throw new AnalyticsException("A filter group has to say whether its conditions are "
                + "joined by AND or by OR.");
        }
        List<FilterClause> children = clause.getClauses();
        if (children == null || children.isEmpty()) {
            // Only the ROOT may be empty, and the root is handled by the caller returning null for
            // an absent filter. An empty group inside a tree is a control the user left half-built,
            // and silently dropping it changes the answer without saying so: an empty OR branch
            // that reads as "no constraint" widens the result rather than narrowing it.
            throw new AnalyticsException("A filter group needs at least one condition in it.");
        }
        StringBuilder sql = new StringBuilder("(");
        String joiner = "";
        for (FilterClause child : children) {
            if (child == null) {
                throw new AnalyticsException("A filter group needs at least one condition in it.");
            }
            String compiled = this.node(child, parameters, depth + 1);
            sql.append(joiner).append(compiled);
            joiner = " " + clause.getOp().name() + " ";
        }
        return sql.append(")").toString();
    }

    /**
     * One condition, as a predicate with its operands held outside the text.
     *
     * Every branch appends its placeholder and its parameter in the same statement, which is what
     * makes the two lists correspond by construction rather than by care. The engine still checks
     * the count against the driver's own parse before executing -- see DuckDbAnalyticsEngine.bind --
     * because "by construction" is a property of code somebody is going to edit.
     */
    private String condition(FilterClause clause, List<Object> parameters) throws AnalyticsException {
        if (clause.getOperator() == null) {
            throw new AnalyticsException("A filter condition has to say what it is testing.");
        }
        ColumnDto column = this.columns.require(clause.getField());
        String field = Columns.quote(column);
        FilterClause.Operator operator = clause.getOperator();

        switch (operator) {
            case IS_NULL:
                return field + " IS NULL";
            case IS_NOT_NULL:
                return field + " IS NOT NULL";
            case EQ:
                parameters.add(this.value(column, single(clause, operator), operator));
                return field + " = " + placeholder(column);
            case NEQ:
                // IS DISTINCT FROM, not <>, and this is a judgement rather than an oversight.
                // SQL's <> is unknown when the column is null, so "status <> closed" silently drops
                // every row whose status is missing -- which is not what anybody means by a filter
                // chip that says "is not closed". A row with no value is not that value.
                // NOT_IN below takes the same reading, so the two negations agree.
                parameters.add(this.value(column, single(clause, operator), operator));
                return field + " IS DISTINCT FROM " + placeholder(column);
            case CONTAINS:
                requireText(column, operator);
                // contains(), not LIKE '%' || ? || '%'. LIKE would make the user's own % and _ into
                // wildcards, so searching for "50%" would match "50" followed by anything, and the
                // usual fix -- escaping them on the way in -- is exactly the escaping this class
                // does not do anywhere else.
                parameters.add(single(clause, operator));
                return "contains(" + field + ", ?)";
            case STARTS_WITH:
                requireText(column, operator);
                parameters.add(single(clause, operator));
                return "starts_with(" + field + ", ?)";
            case GT:
                parameters.add(this.value(column, single(clause, operator), operator));
                return field + " > " + placeholder(column);
            case LT:
                parameters.add(this.value(column, single(clause, operator), operator));
                return field + " < " + placeholder(column);
            case BETWEEN:
                return this.between(clause, column, field, parameters, operator);
            case NUMERIC_RANGE:
                requireNumeric(column, operator);
                return this.between(clause, column, field, parameters, operator);
            case DATE_RANGE:
                return this.dateRange(column, field, parameters, pair(clause, operator));
            case RELATIVE_DATE:
                return this.relativeDate(column, field, parameters, single(clause, operator));
            case IN:
                return this.inList(clause, column, field, parameters, operator, false);
            case NOT_IN:
                return this.inList(clause, column, field, parameters, operator, true);
            default:
                // Unreachable while the enum and this switch agree, and that is the point of it
                // being here: a fifteenth operator added to the enum and forgotten here becomes a
                // refusal rather than a silently dropped condition that widens the result.
                throw new AnalyticsException("That filter is not one this analysis can apply.");
        }
    }

    /** A two-sided bound of the column's own type. */
    private String between(FilterClause clause, ColumnDto column, String field,
        List<Object> parameters, FilterClause.Operator operator) throws AnalyticsException {

        List<String> bounds = pair(clause, operator);
        parameters.add(this.value(column, bounds.get(0), operator));
        parameters.add(this.value(column, bounds.get(1), operator));
        return "(" + field + " >= " + placeholder(column) + " AND "
            + field + " <= " + placeholder(column) + ")";
    }

    /**
     * An inclusive range of days, on a column that holds days or instants.
     *
     * <b>The upper bound is where this earns its own operator.</b> On a DATE column an inclusive
     * range is two comparisons and nothing more. On a TIMESTAMP column, "up to and including the
     * 31st" compared against the 31st is midnight on the 31st, so the whole of the last day is
     * missing from the answer -- a report that is short by a day and looks right. The bound is
     * therefore half-open at the start of the following day, which is the only form in which an
     * inclusive day range means the whole day.
     *
     * The column is cast nowhere. Casting it would defeat the statistics DuckDB prunes Parquet row
     * groups with, and would turn a filter over a hundred million rows into a full scan; the
     * PARAMETER carries the type instead, which is what binding is for.
     */
    private String dateRange(ColumnDto column, String field, List<Object> parameters,
        List<String> bounds) throws AnalyticsException {

        return this.dayWindow(column, field, parameters, asDate(column, bounds.get(0)),
            asDate(column, bounds.get(1)));
    }

    private String dayWindow(ColumnDto column, String field, List<Object> parameters,
        LocalDate from, LocalDate toInclusive) throws AnalyticsException {

        if (from.isAfter(toInclusive)) {
            throw new AnalyticsException("That date range ends before it starts.");
        }
        if (Columns.isTimestamp(column)) {
            parameters.add(from.atStartOfDay());
            parameters.add(toInclusive.plusDays(1).atStartOfDay());
            return "(" + field + " >= ? AND " + field + " < ?)";
        }
        if (!Columns.isDate(column)) {
            throw new AnalyticsException("A date filter needs a date column, and "
                + safeName(column.getName()) + " holds " + column.getType() + ".");
        }
        parameters.add(from);
        parameters.add(toInclusive);
        return "(" + field + " >= ? AND " + field + " <= ?)";
    }

    /**
     * A window named rather than dated, resolved against this compilation's own clock.
     *
     * The vocabulary is closed and small. Every entry is a decision about where a boundary falls --
     * whether "last 7 days" includes today, whether "this month" runs to the end of the month or to
     * today -- and an open-ended expression language would be fourteen such decisions made by
     * whoever typed the string. LAST_N_DAYS:n is the one parametric form, because "last 14 days" is
     * a thing people genuinely want and enumerating every n is not.
     *
     * Every window here ENDS today and includes it, so "last 7 days" is today and the six days
     * before it. The alternative -- ending yesterday, so that a partial today cannot make a trend
     * look like a collapse -- is defensible and is not what a person means when they pick it off a
     * menu at two in the afternoon.
     */
    private String relativeDate(ColumnDto column, String field, List<Object> parameters,
        String token) throws AnalyticsException {

        String window = token.trim().toUpperCase(Locale.ROOT);
        LocalDate from;
        LocalDate to = this.today;
        if (window.startsWith(LAST_N_DAYS)) {
            int days = positiveInt(window.substring(LAST_N_DAYS.length()));
            from = this.today.minusDays(days - 1L);
        } else {
            switch (window) {
                case "TODAY":        from = this.today; break;
                case "YESTERDAY":    from = this.today.minusDays(1); to = from; break;
                case "LAST_7_DAYS":  from = this.today.minusDays(6); break;
                case "LAST_30_DAYS": from = this.today.minusDays(29); break;
                case "LAST_90_DAYS": from = this.today.minusDays(89); break;
                case "THIS_MONTH":   from = this.today.withDayOfMonth(1); break;
                case "LAST_MONTH":
                    from = this.today.minusMonths(1).withDayOfMonth(1);
                    to = from.plusMonths(1).minusDays(1);
                    break;
                case "THIS_YEAR":    from = this.today.withDayOfYear(1); break;
                case "LAST_YEAR":
                    from = this.today.minusYears(1).withDayOfYear(1);
                    to = from.plusYears(1).minusDays(1);
                    break;
                default:
                    throw new AnalyticsException("That is not a relative date this analysis knows. "
                        + "Use TODAY, YESTERDAY, LAST_7_DAYS, LAST_30_DAYS, LAST_90_DAYS, "
                        + "THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR or LAST_N_DAYS:n.");
            }
        }
        this.resolvedWindows.put(window, from + " to " + to);
        return this.dayWindow(column, field, parameters, from, to);
    }

    /**
     * A set membership test, with one placeholder per value and never a joined string.
     *
     * The obvious wrong implementation is to build "IN ('a','b','c')" from the list, and it is
     * wrong twice over: it puts user text in the SQL, and it makes the statement's TEXT depend on
     * the data, so a prepared statement cache would hold one entry per distinct selection.
     *
     * NOT_IN admits nulls, for the reason NEQ does: a row with no value is not one of the values
     * named, and SQL's NOT IN says "unknown" instead. Without the explicit null branch, filtering
     * out two regions would also filter out every row whose region is missing, which nobody asked
     * for and nobody would notice.
     */
    private String inList(FilterClause clause, ColumnDto column, String field,
        List<Object> parameters, FilterClause.Operator operator, boolean negated)
        throws AnalyticsException {

        List<String> values = clause.getValues();
        if (values == null || values.isEmpty()) {
            // Not an empty predicate. "IN ()" is a syntax error, and reading it as "match nothing"
            // or as "match everything" are both answers to a question the caller did not ask.
            throw new AnalyticsException("An " + operator.name().toLowerCase().replace('_', ' ')
                + " filter needs at least one value.");
        }
        if (values.size() > MAX_IN_VALUES) {
            throw new AnalyticsException("An " + operator.name().toLowerCase().replace('_', ' ')
                + " filter takes up to " + MAX_IN_VALUES + " values.");
        }
        StringBuilder sql = new StringBuilder(field);
        sql.append(negated ? " NOT IN (" : " IN (");
        for (int i = 0; i < values.size(); i++) {
            String raw = values.get(i);
            if (raw == null) {
                // A null in the list would bind as NULL, and "x IN (NULL)" is never true while
                // "x NOT IN (NULL)" is never true either -- so one null quietly empties the result.
                // IS_NULL is the operator for that question.
                throw new AnalyticsException("An "
                    + operator.name().toLowerCase().replace('_', ' ')
                    + " filter cannot contain an empty value. Use is null instead.");
            }
            sql.append(i == 0 ? placeholder(column) : ", " + placeholder(column));
            parameters.add(this.value(column, raw, operator));
        }
        sql.append(")");
        return negated ? "(" + field + " IS NULL OR " + sql + ")" : sql.toString();
    }

    /**
     * One value, as an object of the column's own Java type.
     *
     * This is where a filter over a numeric column stops being able to fail in the engine. Measured
     * on 1.1.3: a String bound against a DOUBLE is a Binder Error, not a comparison. Coercing here
     * turns "twenty" on an amount column into a sentence, and it also means the parameter that
     * reaches the driver has a type the driver did not have to infer from the text.
     */
    /**
     * The placeholder for one bound value, cast where the engine will not compare without it.
     *
     * TIME is bound as text (see value()), and DuckDB compares text to a TIME implicitly for "="
     * and refuses to for ">" -- "Cannot compare values of type TIME and type VARCHAR, an explicit
     * cast is required". So the cast goes in the SQL rather than in the parameter, which keeps the
     * value bound and out of the statement text where it belongs. Every other type binds as its
     * own class and needs nothing.
     */
    private static String placeholder(ColumnDto column) {
        return Columns.isTime(column) ? "CAST(? AS TIME)" : "?";
    }

    private Object value(ColumnDto column, String raw, FilterClause.Operator operator)
        throws AnalyticsException {

        if (Columns.isNumeric(column)) {
            try {
                return new BigDecimal(raw.trim());
            } catch (NumberFormatException ex) {
                throw new AnalyticsException(safeName(column.getName()) + " holds numbers, and \""
                    + safeName(raw) + "\" is not one.");
            }
        }
        if (Columns.isDate(column)) {
            return asDate(column, raw);
        }
        if (Columns.isTimestamp(column)) {
            return asTimestamp(column, raw);
        }
        if (Columns.isTime(column)) {
            // Parsed to VALIDATE, bound as TEXT. duckdb_jdbc 1.1.3 refuses a java.time.LocalTime
            // parameter outright -- "Invalid Input Error: Unsupported parameter type" -- so every
            // filter carrying a value against a TIME column died in the engine, and explain()
            // mapped that to "This file could not be read as CSV. It may be malformed", which
            // blamed the customer's data for a binding bug of ours.
            //
            // java.sql.Time is the obvious repair and is WORSE: measured on 1.1.3 it binds without
            // complaint and matches NOTHING. A filter that silently returns no rows is harder to
            // notice than one that throws. A plain String matches correctly, so that is bound.
            //
            // TIMETZ lands here too. Its offset survives as text where LocalTime could not parse
            // it at all, so this widens what is accepted rather than narrowing it.
            String time = raw.trim();
            try {
                LocalTime.parse(time);
            } catch (DateTimeParseException ex) {
                if (!OFFSET_TIME.matcher(time).matches()) {
                    throw new AnalyticsException(safeName(column.getName())
                        + " holds times, and \"" + safeName(raw) + "\" is not one.");
                }
            }
            return time;
        }
        if (Columns.isBoolean(column)) {
            String flag = raw.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(flag) || "false".equals(flag)) {
                return Boolean.valueOf(flag);
            }
            throw new AnalyticsException(safeName(column.getName())
                + " is true or false, and \"" + safeName(raw) + "\" is neither.");
        }
        // VARCHAR and everything DuckDB reads as text -- UUID, BLOB, an enum. The value goes down
        // as a String and is compared as one, which is what the column holds. It is still bound,
        // so what it says has no bearing on what the statement does.
        return raw;
    }

    private static LocalDate asDate(ColumnDto column, String raw) throws AnalyticsException {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new AnalyticsException(safeName(column.getName()) + " holds dates, and \""
                + safeName(raw) + "\" is not one. Dates are written 2024-01-31.");
        }
    }

    private static LocalDateTime asTimestamp(ColumnDto column, String raw) throws AnalyticsException {
        String value = raw.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            try {
                // A date on a timestamp column means the start of that day, which is what a person
                // typing 2024-01-31 into a filter over a timestamp means by it.
                return LocalDate.parse(raw.trim()).atStartOfDay();
            } catch (DateTimeParseException ignored) {
                throw new AnalyticsException(safeName(column.getName()) + " holds timestamps, and \""
                    + safeName(raw) + "\" is not one. Timestamps are written "
                    + "2024-01-31 14:30:00.");
            }
        }
    }

    private static void requireText(ColumnDto column, FilterClause.Operator operator)
        throws AnalyticsException {

        if (!Columns.isText(column)) {
            throw new AnalyticsException(operator.name().toLowerCase().replace('_', ' ')
                + " looks inside text, and " + safeName(column.getName()) + " holds "
                + column.getType() + ".");
        }
    }

    private static void requireNumeric(ColumnDto column, FilterClause.Operator operator)
        throws AnalyticsException {

        if (!Columns.isNumeric(column)) {
            throw new AnalyticsException("A numeric range needs a numeric column, and "
                + safeName(column.getName()) + " holds " + column.getType() + ".");
        }
    }

    private static String single(FilterClause clause, FilterClause.Operator operator)
        throws AnalyticsException {

        String value = clause.getValue();
        if (value == null && clause.getValues() != null && clause.getValues().size() == 1) {
            value = clause.getValues().get(0);
        }
        if (value == null) {
            throw new AnalyticsException("A " + operator.name().toLowerCase().replace('_', ' ')
                + " filter needs a value. Use is null to test for a missing one.");
        }
        return value;
    }

    private static List<String> pair(FilterClause clause, FilterClause.Operator operator)
        throws AnalyticsException {

        List<String> values = clause.getValues();
        if (values == null || values.size() != 2 || values.get(0) == null || values.get(1) == null) {
            throw new AnalyticsException("A " + operator.name().toLowerCase().replace('_', ' ')
                + " filter needs exactly two values: the low bound and the high one.");
        }
        return values;
    }

    private static int positiveInt(String raw) throws AnalyticsException {
        try {
            int days = Integer.parseInt(raw.trim());
            if (days >= 1 && days <= 3650) {
                return days;
            }
        } catch (NumberFormatException ignored) {
            // Falls through to the same refusal: a window of "banana" days and a window of a
            // million days are the same mistake to whoever has to fix it.
        }
        throw new AnalyticsException("A relative window of days is a whole number between 1 and "
            + "3650.");
    }

    /**
     * A string from a request, made safe to put in a message that will be rendered somewhere.
     *
     * Not escaping and not validation -- the value has already been refused by the time this is
     * called. It exists because the refusal travels back through a JSON envelope into a page, and
     * a module that echoes an arbitrary request string verbatim has made the rendering layer's
     * escaping into its own problem. Everything outside a plain name becomes a question mark, so
     * the reader still sees roughly what they typed and no markup survives.
     */
    static String safeName(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() > NAME_ECHO_LIMIT) {
            trimmed = trimmed.substring(0, NAME_ECHO_LIMIT) + "...";
        }
        StringBuilder safe = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char letter = trimmed.charAt(i);
            boolean plain = Character.isLetterOrDigit(letter) || letter == '_' || letter == '-'
                || letter == '.' || letter == ' ';
            safe.append(plain ? letter : '?');
        }
        return safe.toString();
    }

    /**
     * The dataset's own columns, and the only place an identifier may come from.
     *
     * <b>This is the allow-list, and it is derived from the data rather than from a pattern.</b>
     * The list is a DESCRIBE of the dataset, run inside the session the query will run in, so it
     * describes the file that is about to be read and not one that was read earlier. Any name a
     * request asks for is looked UP in it and what comes back out is the schema's own String. A
     * request cannot contribute characters to the SQL by naming a field, only choose among the
     * strings the file already contained -- which is why an expression, a location, a comment or a
     * closing quote in the field position is not a hole to be escaped but a name that is not there.
     *
     * The lookup is case-insensitive and the answer is still the schema's spelling. A user picking
     * "Region" off a menu built from a header that says "region" is not attacking anything, and the
     * fold happens on the way IN; the way out is unchanged.
     *
     * Shared by {@link FilterCompiler} and {@link AnalysisQueryBuilder} on purpose. A dimension, a
     * measure's field and a filter's field are the same kind of thing and a second resolver for any
     * of them is a second allow-list to keep in step.
     */
    public static final class Columns {

        private final List<ColumnDto> all;
        private final Map<String, ColumnDto> byName = new HashMap<>();
        private final Map<String, ColumnDto> byFoldedName = new HashMap<>();
        private final Set<String> ambiguous = new HashSet<>();

        private Columns(List<ColumnDto> columns) {
            this.all = columns;
            for (ColumnDto column : columns) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                this.byName.put(column.getName(), column);
                String folded = column.getName().toLowerCase(Locale.ROOT);
                if (this.byFoldedName.put(folded, column) != null) {
                    // Two columns whose names differ only in case. DuckDB permits it and a Parquet
                    // file written by a case-sensitive producer will have it. The fold cannot pick
                    // between them, so it refuses to: the exact spelling still resolves, and only
                    // the case-insensitive convenience is withdrawn for that one name.
                    this.ambiguous.add(folded);
                }
            }
        }

        public static Columns of(List<ColumnDto> columns) throws AnalyticsException {
            if (columns == null || columns.isEmpty()) {
                throw new AnalyticsException("This dataset has no columns to analyse.");
            }
            for (ColumnDto column : columns) {
                // The one thing a column name is not allowed to contain, and the only check made
                // against the SCHEMA rather than against the request.
                //
                // A statement is text, and text with a NUL in it is two different texts: measured
                // on 1.1.3, DuckDB's parser and its executor both stop at the byte -- which is what
                // StatementGate's NO_NUL_BYTE rule rests on. Here the byte would arrive inside an
                // identifier the builder is about to quote, truncating the composed statement at
                // that point. The two layers truncate in the SAME place today, so this fails closed
                // either way; refusing it outright means the argument does not have to be made
                // again the day one of them changes. A CSV header can contain anything the file
                // does, and this one is a file nobody can analyse rather than a request to refuse.
                if (column != null && column.getName() != null
                    && column.getName().indexOf('\0') >= 0) {
                    throw new AnalyticsException("A column of this dataset has a character in its "
                        + "name that a query cannot contain, so it cannot be analysed. Check the "
                        + "file's header row.");
                }
            }
            return new Columns(columns);
        }

        /** Every column, in the order the dataset declares them. */
        public List<ColumnDto> all() {
            return this.all;
        }

        /**
         * The column a request named, or a refusal.
         *
         * The refusal is the same sentence whether the name is absent, misspelled or an attempt at
         * something else, because from here they are the same event: a name that is not in the
         * file. It does not list the columns that ARE there -- the caller already holds the schema
         * it built its own menu from, and a wide file would put four hundred names in an error
         * toast.
         */
        public ColumnDto require(String requested) throws AnalyticsException {
            if (requested == null || requested.trim().isEmpty()) {
                throw new AnalyticsException("A filter has to name the column it is about.");
            }
            String name = requested.trim();
            ColumnDto exact = this.byName.get(name);
            if (exact != null) {
                return exact;
            }
            String folded = name.toLowerCase(Locale.ROOT);
            ColumnDto insensitive = this.ambiguous.contains(folded)
                ? null : this.byFoldedName.get(folded);
            if (insensitive != null) {
                return insensitive;
            }
            throw new AnalyticsException("This dataset has no column called \""
                + safeName(name) + "\".");
        }

        /**
         * The column's name as an identifier DuckDB will accept.
         *
         * Doubling the quotes is what makes a name usable, not what makes it safe: safety is that
         * the string came from the DESCRIBE above. Both matter -- a CSV header is allowed to say
         * a"b, and a file with one in it should be analysable rather than an error nobody can fix.
         */
        public static String quote(ColumnDto column) {
            return "\"" + column.getName().replace("\"", "\"\"") + "\"";
        }

        /** DuckDB's own type name, upper-cased, with any DECIMAL(p,s) precision left off. */
        private static String baseType(ColumnDto column) {
            String type = column.getType() == null ? "" : column.getType().toUpperCase(Locale.ROOT);
            int bracket = type.indexOf('(');
            return (bracket < 0 ? type : type.substring(0, bracket)).trim();
        }

        public static boolean isNumeric(ColumnDto column) {
            switch (baseType(column)) {
                case "TINYINT": case "SMALLINT": case "INTEGER": case "BIGINT": case "HUGEINT":
                case "UTINYINT": case "USMALLINT": case "UINTEGER": case "UBIGINT": case "UHUGEINT":
                case "FLOAT": case "REAL": case "DOUBLE": case "DECIMAL": case "NUMERIC":
                    return true;
                default:
                    return false;
            }
        }

        public static boolean isDate(ColumnDto column) {
            return "DATE".equals(baseType(column));
        }

        public static boolean isTimestamp(ColumnDto column) {
            String type = baseType(column);
            return type.startsWith("TIMESTAMP");
        }

        public static boolean isTime(ColumnDto column) {
            String type = baseType(column);
            return "TIME".equals(type) || "TIME WITH TIME ZONE".equals(type);
        }

        public static boolean isBoolean(ColumnDto column) {
            return "BOOLEAN".equals(baseType(column));
        }

        public static boolean isText(ColumnDto column) {
            String type = baseType(column);
            return "VARCHAR".equals(type) || "TEXT".equals(type) || "STRING".equals(type);
        }

        /**
         * Whether a column can be ordered, which is what min, max and a median need of it.
         *
         * Nested types cannot -- DuckDB will compare two LISTs but the answer is not a thing a
         * reader would call a minimum -- so they are excluded here rather than allowed to produce a
         * number nobody can interpret.
         */
        public static boolean isOrderable(ColumnDto column) {
            String type = baseType(column);
            return !type.isEmpty() && !type.startsWith("STRUCT") && !type.startsWith("MAP")
                && !type.startsWith("UNION") && !type.endsWith("[]");
        }
    }
}
