package process.pipeline.data;

import org.barco.platform.api.ApiTimes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.HashMap;
import java.io.IOException;

/**
 * A Compute formula (MIG-255): a small, closed language over one row's columns -- nothing in it can reach outside the row.
 *
 * <ul>
 *   <li>values: numbers (2, 0.785), text ('stable' or "stable"), true, false, null, and columns by name (length_cm, or
 *       `item length` in backticks for a name with spaces);</li>
 *   <li>arithmetic + - * / and a leading minus; comparisons == != &gt; &gt;= &lt; &lt;=; and, or, not; parentheses;</li>
 *   <li>functions: abs, coalesce, concat, days_between (ISO dates), if(condition, then, otherwise), is_null, max, min,
 *       number, regex_extract(text, pattern) (the first group of the first match, or the whole match; null when
 *       none), round(value[, places]), text, today() (the business date, YYYY-MM-DD, America/Chicago);</li>
 *   <li>MIG-365 numbers: floor(x) and ceil(x) (a whole number), mod(a, b) (the remainder with b's sign, as a
 *       spreadsheet's MOD; null when b is 0);</li>
 *   <li>MIG-365 dates: date_add(date, amount[, unit]), date_diff(from, to[, unit]) (whole units from one to the other)
 *       and date_trunc(date, unit) (the start of its year, quarter, month, week (Monday), day, hour or minute). unit is
 *       day (the default), week, month, quarter, year, hour, minute or second, singular or plural. A date is
 *       YYYY-MM-DD, a date and time YYYY-MM-DDTHH:MM[:SS] (or with a space), with Z or an offset when it has one; the
 *       answer has the same form as the date given (a date plus hours becomes a date and time);</li>
 *   <li>MIG-346 JSON: json_get(json, path) (the value at a path such as items[0].name or $.items[0].name: a number,
 *       text or true/false as itself, an object or array as its JSON text; null when absent), json_array_length(json[,
 *       path]) and json_keys(json[, path]) (an object's keys as a JSON array of text). json is a column holding JSON
 *       text (an API's nested value is kept that way); text that is not JSON fails the row and names its column.</li>
 * </ul>
 *
 * A column read as CSV text is a number where it reads as one. An empty or null operand makes arithmetic null (never an
 * error), and dividing by zero is null. A comparison with null or empty is false; two numbers compare as numbers,
 * anything else as text (so ISO dates compare in date order). A text that is not a number, used as one, fails the row
 * and names its column.
 */
public final class Expression {

    static final List<String> FUNCTIONS = Collections.unmodifiableList(Arrays.asList("abs", "ceil", "coalesce", "concat", "date_add",
        "date_diff", "date_trunc", "days_between", "floor", "if", "is_null", "json_array_length", "json_get", "json_keys", "max", "min", "mod",
        "number", "regex_extract", "round", "text", "today"));

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    /** The business clock's zone: today() is the date there, as {{date}} is. */
    static final ZoneId BUSINESS_ZONE = ApiTimes.PLATFORM_ZONE;

    private final Node root;
    private final List<String> columns;

    private Expression(Node root, List<String> columns) {
        this.root = root;
        this.columns = columns;
    }

    /** @throws IllegalArgumentException the formula does not parse; the message says what, and where (1-based) */
    public static Expression parse(String formula) {
        Parser parser = new Parser(formula == null ? "" : formula);
        Node root = parser.expression();
        parser.expectEnd();
        return new Expression(root, new ArrayList<>(parser.columns));
    }

    /** The columns the formula reads, in the order it names them. */
    public List<String> columns() {
        return this.columns;
    }

    /** @throws IllegalArgumentException a text used as a number or a date that is not one, naming its column */
    public Object evaluate(Map<String, Object> row) {
        return this.root.eval(row);
    }

    // ------------------------------------------------------------------------------------------------------------- nodes

    private interface Node {
        Object eval(Map<String, Object> row);

        /** What a value is called in an error: its column, or "a value". */
        default String label() {
            return "a value";
        }
    }

    private static final class Literal implements Node {
        private final Object value;

        Literal(Object value) {
            this.value = value;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            return this.value;
        }
    }

    private static final class Column implements Node {
        private final String name;

        Column(String name) {
            this.name = name;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            return row.get(this.name);
        }

        @Override
        public String label() {
            return this.name;
        }
    }

    private static final class Call implements Node {
        private final String name;
        private final List<Node> args;

        Call(String name, List<Node> args) {
            this.name = name;
            this.args = args;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            switch (this.name) {
                case "if":
                    return truthy(this.args.get(0).eval(row)) ? this.args.get(1).eval(row) : this.args.get(2).eval(row);
                case "coalesce":
                    for (Node arg : this.args) {
                        Object value = arg.eval(row);
                        if (!isNull(value)) {
                            return value;
                        }
                    }
                    return null;
                case "is_null":
                    return isNull(this.args.get(0).eval(row));
                case "concat": {
                    StringBuilder text = new StringBuilder();
                    for (Node arg : this.args) {
                        Object value = arg.eval(row);
                        text.append(value == null ? "" : textOf(value));
                    }
                    return text.toString();
                }
                case "text": {
                    Object value = this.args.get(0).eval(row);
                    return value == null ? null : textOf(value);
                }
                case "number":
                    return number(this.args.get(0), row);
                case "abs": {
                    Double value = number(this.args.get(0), row);
                    return value == null ? null : Math.abs(value);
                }
                case "min":
                case "max": {
                    Double best = null;
                    for (Node arg : this.args) {
                        Double value = number(arg, row);
                        if (value != null && (best == null || ("min".equals(this.name) ? value < best : value > best))) {
                            best = value;
                        }
                    }
                    return best;
                }
                case "round": {
                    Double value = number(this.args.get(0), row);
                    Double places = this.args.size() > 1 ? number(this.args.get(1), row) : Double.valueOf(0);
                    if (value == null || places == null || value.isNaN() || value.isInfinite()) {
                        return null;
                    }
                    BigDecimal rounded = BigDecimal.valueOf(value).setScale(Math.max(0, Math.min(10, places.intValue())), RoundingMode.HALF_UP);
                    return rounded.scale() == 0 ? (Object) rounded.longValueExact() : (Object) rounded.doubleValue();
                }
                case "today":
                    return LocalDate.now(BUSINESS_ZONE).toString();
                case "regex_extract": {
                    Object value = this.args.get(0).eval(row);
                    Object pattern = this.args.get(1).eval(row);
                    if (isNull(value) || isNull(pattern)) {
                        return null;
                    }
                    Matcher m = pattern(textOf(pattern)).matcher(textOf(value));
                    if (!m.find()) {
                        return null;
                    }
                    return m.groupCount() >= 1 ? m.group(1) : m.group();
                }
                case "days_between": {
                    LocalDate from = date(this.args.get(0), row);
                    LocalDate to = date(this.args.get(1), row);
                    return from == null || to == null ? null : ChronoUnit.DAYS.between(from, to);
                }
                case "floor":
                case "ceil": {
                    Double value = number(this.args.get(0), row);
                    if (value == null || value.isNaN() || value.isInfinite()) {
                        return null;
                    }
                    return whole("floor".equals(this.name) ? Math.floor(value) : Math.ceil(value));
                }
                case "mod": {
                    Double a = number(this.args.get(0), row);
                    Double b = number(this.args.get(1), row);
                    if (a == null || b == null || b == 0 || a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) {
                        return null;
                    }
                    if (a == Math.rint(a) && b == Math.rint(b) && Math.abs(a) < 9e15 && Math.abs(b) < 9e15) {
                        return Math.floorMod(a.longValue(), b.longValue());
                    }
                    double r = a - b * Math.floor(a / b);
                    return r;
                }
                case "date_add": {
                    Moment at = Moment.of(this.args.get(0), row);
                    Double amount = number(this.args.get(1), row);
                    if (at == null || amount == null) {
                        return null;
                    }
                    if (amount != Math.rint(amount)) {
                        throw new IllegalArgumentException(String.format("date_add adds whole units; %s is not one.", textOf(amount)));
                    }
                    return at.plus(amount.longValue(), unit(this.args.size() > 2 ? this.args.get(2).eval(row) : null, "date_add")).text();
                }
                case "date_diff": {
                    Moment from = Moment.of(this.args.get(0), row);
                    Moment to = Moment.of(this.args.get(1), row);
                    if (from == null || to == null) {
                        return null;
                    }
                    return Moment.between(from, to, unit(this.args.size() > 2 ? this.args.get(2).eval(row) : null, "date_diff"));
                }
                case "date_trunc": {
                    // date_trunc(date, unit); date_trunc('month', date) -- SQL's order -- is read the same way.
                    Node dateArg = this.args.get(0);
                    Node unitArg = this.args.get(1);
                    Object first = dateArg.eval(row);
                    if (first instanceof String && UNITS.containsKey(((String) first).trim().toLowerCase())
                        && !(unitArg.eval(row) instanceof String && UNITS.containsKey(((String) unitArg.eval(row)).trim().toLowerCase()))) {
                        Node swap = dateArg;
                        dateArg = unitArg;
                        unitArg = swap;
                    }
                    Moment at = Moment.of(dateArg, row);
                    return at == null ? null : at.truncated(unit(unitArg.eval(row), "date_trunc")).text();
                }
                case "json_get": {
                    JsonNode at = json(this.args.get(0), this.args.size() > 1 ? this.args.get(1).eval(row) : null, row);
                    return at == null ? null : Values.scalar(at);
                }
                case "json_array_length": {
                    JsonNode at = json(this.args.get(0), this.args.size() > 1 ? this.args.get(1).eval(row) : null, row);
                    if (at == null) {
                        return null;
                    }
                    if (!at.isArray()) {
                        throw new IllegalArgumentException(String.format("The value in %s is not a JSON array.", this.args.get(0).label()));
                    }
                    return (long) at.size();
                }
                case "json_keys": {
                    JsonNode at = json(this.args.get(0), this.args.size() > 1 ? this.args.get(1).eval(row) : null, row);
                    if (at == null) {
                        return null;
                    }
                    if (!at.isObject()) {
                        throw new IllegalArgumentException(String.format("The value in %s is not a JSON object.", this.args.get(0).label()));
                    }
                    ArrayNode keys = Values.JSON.createArrayNode();
                    at.fieldNames().forEachRemaining(keys::add);
                    return keys.toString();
                }
                default:
                    throw new IllegalStateException("Unknown function " + this.name);
            }
        }
    }

    private static final class Unary implements Node {
        private final String op;
        private final Node operand;

        Unary(String op, Node operand) {
            this.op = op;
            this.operand = operand;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            if ("not".equals(this.op)) {
                return !truthy(this.operand.eval(row));
            }
            Double value = number(this.operand, row);
            return value == null ? null : -value;
        }
    }

    private static final class Binary implements Node {
        private final String op;
        private final Node left;
        private final Node right;

        Binary(String op, Node left, Node right) {
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            switch (this.op) {
                case "and":
                    return truthy(this.left.eval(row)) && truthy(this.right.eval(row));
                case "or":
                    return truthy(this.left.eval(row)) || truthy(this.right.eval(row));
                case "==": case "!=": case ">": case ">=": case "<": case "<=":
                    return compare(this.op, this.left.eval(row), this.right.eval(row));
                default: {
                    Double a = number(this.left, row);
                    Double b = number(this.right, row);
                    if (a == null || b == null) {
                        return null;
                    }
                    switch (this.op) {
                        case "+": return a + b;
                        case "-": return a - b;
                        case "*": return a * b;
                        default: return b == 0 ? null : a / b;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------ semantics

    static boolean isNull(Object value) {
        return value == null || (value instanceof String && ((String) value).trim().isEmpty());
    }

    static boolean truthy(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (isNull(value)) {
            return false;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        return !"false".equalsIgnoreCase(value.toString().trim());
    }

    /** A number, or null for null/empty; a text that is not one names where it came from. */
    private static Double number(Node node, Map<String, Object> row) {
        Object value = node.eval(row);
        if (isNull(value)) {
            return null;
        }
        Double number = numberOrNull(value);
        if (number == null) {
            throw new IllegalArgumentException(String.format("'%s' in %s is not a number.", value, node.label()));
        }
        return number;
    }

    private static Double numberOrNull(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Boolean || isNull(value)) {
            return null;
        }
        String text = value.toString().trim();
        if (!mayBeDouble(text)) {
            // Answered without throwing: a NumberFormatException per cell (every 'north' or '2024-01-01' a filter
            // compares, twice a row) was most of a text comparison's time -- the MIG-344 fix Values.number has.
            return null;
        }
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * Whether {@link Double#valueOf(String)} might accept the (trimmed) text; false only when it certainly refuses it, so
     * the answer of {@link #numberOrNull} is unchanged. Its grammar: an optional sign, then NaN, Infinity, a hexadecimal
     * float (0x...), or a decimal -- BigDecimal's grammar ({@link Values#readsAsNumber}) with an optional f, F, d or D.
     */
    static boolean mayBeDouble(String text) {
        int start = !text.isEmpty() && (text.charAt(0) == '+' || text.charAt(0) == '-') ? 1 : 0;
        if (start >= text.length()) {
            return false;
        }
        String body = text.substring(start);
        char first = body.charAt(0);
        if (first == 'N') {
            return body.equals("NaN");
        }
        if (first == 'I') {
            return body.equals("Infinity");
        }
        if (body.startsWith("0x") || body.startsWith("0X")) {
            return true;
        }
        char last = body.charAt(body.length() - 1);
        if (last == 'f' || last == 'F' || last == 'd' || last == 'D') {
            body = body.substring(0, body.length() - 1);
        }
        return !body.isEmpty() && Values.readsAsNumber(body);
    }

    private static Pattern pattern(String text) {
        if (PATTERNS.size() > 1000) {
            PATTERNS.clear();
        }
        try {
            return PATTERNS.computeIfAbsent(text, Pattern::compile);
        } catch (PatternSyntaxException invalid) {
            throw new IllegalArgumentException(String.format("'%s' is not a valid pattern: %s.", text, invalid.getDescription()));
        }
    }

    private static LocalDate date(Node node, Map<String, Object> row) {
        Object value = node.eval(row);
        if (isNull(value)) {
            return null;
        }
        String text = value.toString().trim();
        try {
            return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException notADate) {
            throw new IllegalArgumentException(String.format("'%s' in %s is not a date (YYYY-MM-DD).", value, node.label()));
        }
    }

    /** A whole double as a long when it fits (floor, ceil): 3.0 is 3, as round(x) gives. */
    private static Object whole(double value) {
        return Math.abs(value) < 9e15 ? (Object) (long) value : (Object) value;
    }

    /** The units date_add, date_diff and date_trunc take, by every word they accept. */
    static final Map<String, String> UNITS;

    static {
        Map<String, String> units = new HashMap<>();
        for (String unit : new String[] {"second", "minute", "hour", "day", "week", "month", "quarter", "year"}) {
            units.put(unit, unit);
            units.put(unit + "s", unit);
        }
        UNITS = Collections.unmodifiableMap(units);
    }

    private static String unit(Object word, String function) {
        if (isNull(word)) {
            if ("date_trunc".equals(function)) {
                throw new IllegalArgumentException("date_trunc needs a unit: year, quarter, month, week, day, hour or minute.");
            }
            return "day";
        }
        String unit = UNITS.get(word.toString().trim().toLowerCase());
        if (unit == null) {
            throw new IllegalArgumentException(String.format("'%s' is not a unit; use second, minute, hour, day, week, month, quarter or "
                + "year.", word));
        }
        return unit;
    }

    /**
     * A date, a date and time, or a date and time with its offset (MIG-365), kept in the form it came in, so what
     * date_add and date_trunc answer reads like what they were given.
     */
    static final class Moment {
        private final LocalDate date;
        private final LocalDateTime local;
        private final OffsetDateTime offset;
        private final boolean seconds;

        private Moment(LocalDate date, LocalDateTime local, OffsetDateTime offset, boolean seconds) {
            this.date = date;
            this.local = local;
            this.offset = offset;
            this.seconds = seconds;
        }

        static Moment of(Node node, Map<String, Object> row) {
            Object value = node.eval(row);
            if (isNull(value)) {
                return null;
            }
            String text = value.toString().trim();
            try {
                if (text.length() <= 10) {
                    return new Moment(LocalDate.parse(text), null, null, false);
                }
                String iso = text.charAt(10) == ' ' ? text.substring(0, 10) + 'T' + text.substring(11) : text;
                boolean seconds = iso.length() > 16 && iso.charAt(16) == ':';
                char last = iso.charAt(iso.length() - 1);
                int sign = Math.max(iso.lastIndexOf('+'), iso.lastIndexOf('-'));
                if (last == 'Z' || last == 'z' || sign > 10) {
                    return new Moment(null, null, OffsetDateTime.parse(iso.replace('z', 'Z')), seconds);
                }
                return new Moment(null, LocalDateTime.parse(iso), null, seconds);
            } catch (DateTimeParseException | StringIndexOutOfBoundsException notADate) {
                throw new IllegalArgumentException(String.format("'%s' in %s is not a date (YYYY-MM-DD, or YYYY-MM-DDTHH:MM:SS).", value,
                    node.label()));
            }
        }

        private static boolean timeUnit(String unit) {
            return "hour".equals(unit) || "minute".equals(unit) || "second".equals(unit);
        }

        private static Temporal add(Temporal at, long amount, String unit) {
            switch (unit) {
                case "quarter":
                    return at.plus(3 * amount, ChronoUnit.MONTHS);
                case "week":
                    return at.plus(amount, ChronoUnit.WEEKS);
                default:
                    return at.plus(amount, chrono(unit));
            }
        }

        private static ChronoUnit chrono(String unit) {
            switch (unit) {
                case "second": return ChronoUnit.SECONDS;
                case "minute": return ChronoUnit.MINUTES;
                case "hour": return ChronoUnit.HOURS;
                case "week": return ChronoUnit.WEEKS;
                case "month": return ChronoUnit.MONTHS;
                case "year": return ChronoUnit.YEARS;
                default: return ChronoUnit.DAYS;
            }
        }

        Moment plus(long amount, String unit) {
            if (this.date != null) {
                if (timeUnit(unit)) {
                    return new Moment(null, (LocalDateTime) add(this.date.atStartOfDay(), amount, unit), null, "second".equals(unit));
                }
                return new Moment((LocalDate) add(this.date, amount, unit), null, null, false);
            }
            boolean seconds = this.seconds || "second".equals(unit);
            return this.local != null ? new Moment(null, (LocalDateTime) add(this.local, amount, unit), null, seconds)
                : new Moment(null, null, (OffsetDateTime) add(this.offset, amount, unit), seconds);
        }

        Moment truncated(String unit) {
            if (this.date != null) {
                return timeUnit(unit) ? this : new Moment(startOf(this.date, unit), null, null, false);
            }
            LocalDateTime local = this.local != null ? this.local : wallClock(this.offset);
            LocalDateTime cut;
            if ("second".equals(unit) || "minute".equals(unit) || "hour".equals(unit)) {
                cut = local.truncatedTo(chrono(unit));
            } else {
                cut = startOf(local.toLocalDate(), unit).atStartOfDay();
            }
            return this.local != null ? new Moment(null, cut, null, this.seconds)
                : new Moment(null, null, cut.atOffset(this.offset.getOffset()), this.seconds);
        }

        private static LocalDate startOf(LocalDate date, String unit) {
            switch (unit) {
                case "year": return date.withDayOfYear(1);
                case "quarter": return date.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1).withDayOfMonth(1);
                case "month": return date.withDayOfMonth(1);
                case "week": return date.minusDays(date.getDayOfWeek().getValue() - 1L);
                default: return date;
            }
        }

        /** Whole units from one to the other; a date is its day's start, and two offsets compare as instants. */
        static long between(Moment from, Moment to, String unit) {
            Temporal a;
            Temporal b;
            if (from.offset != null && to.offset != null) {
                a = from.offset;
                b = to.offset;
            } else {
                a = from.localTime();
                b = to.localTime();
            }
            if ("quarter".equals(unit)) {
                return ChronoUnit.MONTHS.between(a, b) / 3;
            }
            return chrono(unit).between(a, b);
        }

        private LocalDateTime localTime() {
            return this.date != null ? this.date.atStartOfDay() : this.local != null ? this.local : wallClock(this.offset);
        }

        /** An offset date and time's own wall clock, as written (no zone of this machine's is read). */
        private static LocalDateTime wallClock(OffsetDateTime at) {
            return LocalDateTime.of(at.toLocalDate(), at.toLocalTime());
        }

        String text() {
            if (this.date != null) {
                return this.date.toString();
            }
            DateTimeFormatter format = this.seconds ? LOCAL_SECONDS : LOCAL_MINUTES;
            return this.local != null ? this.local.format(format)
                : wallClock(this.offset).format(format) + (this.offset.getOffset().getTotalSeconds() == 0 ? "Z"
                    : this.offset.getOffset().getId());
        }
    }

    private static final DateTimeFormatter LOCAL_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter LOCAL_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** The last JSON text this thread parsed, so several json_ functions over one column in a row parse it once. */
    private static final ThreadLocal<Object[]> LAST_JSON = new ThreadLocal<>();

    /** The JSON value at {@code path} (Values.at's syntax, an optional leading $.) of the JSON text a node gives; null when absent. */
    private static JsonNode json(Node node, Object path, Map<String, Object> row) {
        Object value = node.eval(row);
        if (isNull(value)) {
            return null;
        }
        JsonNode parsed;
        if (value instanceof Number || value instanceof Boolean) {
            parsed = Values.JSON.valueToTree(value);
        } else {
            String text = value.toString();
            Object[] last = LAST_JSON.get();
            if (last != null && last[0].equals(text)) {
                parsed = (JsonNode) last[1];
            } else {
                try {
                    parsed = Values.JSON.readTree(text);
                } catch (IOException notJson) {
                    throw new IllegalArgumentException(String.format("The value in %s is not JSON.", node.label()));
                }
                if (parsed == null) {
                    throw new IllegalArgumentException(String.format("The value in %s is not JSON.", node.label()));
                }
                LAST_JSON.set(new Object[] {text, parsed});
            }
        }
        String where = isNull(path) ? "" : path.toString().trim();
        if (where.equals("$")) {
            where = "";
        } else if (where.startsWith("$.")) {
            where = where.substring(2);
        } else if (where.startsWith("$[")) {
            where = where.substring(1);
        }
        JsonNode at = Values.at(parsed, where);
        return at == null || at.isMissingNode() || at.isNull() ? null : at;
    }

    private static boolean compare(String op, Object left, Object right) {
        if (isNull(left) || isNull(right)) {
            return false;
        }
        int order;
        Double a = numberOrNull(left);
        Double b = numberOrNull(right);
        if (a != null && b != null) {
            order = Double.compare(a, b);
        } else if (left instanceof Boolean || right instanceof Boolean) {
            order = truthy(left) == truthy(right) ? 0 : 1;
            if (!"==".equals(op) && !"!=".equals(op)) {
                return false;
            }
        } else {
            order = textOf(left).compareTo(textOf(right));
        }
        switch (op) {
            case "==": return order == 0;
            case "!=": return order != 0;
            case ">": return order > 0;
            case ">=": return order >= 0;
            case "<": return order < 0;
            default: return order <= 0;
        }
    }

    /** A value as text; a whole number without its ".0". */
    static String textOf(Object value) {
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
        }
        return value.toString();
    }

    // --------------------------------------------------------------------------------------------------------- parser

    private static final class Parser {
        private final String text;
        private final Set<String> columns = new LinkedHashSet<>();
        private int at;

        Parser(String text) {
            this.text = text;
        }

        Node expression() {
            return this.or();
        }

        void expectEnd() {
            this.skipSpace();
            if (this.at < this.text.length()) {
                throw this.error("Unexpected '" + this.text.charAt(this.at) + "'");
            }
        }

        private Node or() {
            Node left = this.and();
            while (this.word("or")) {
                left = new Binary("or", left, this.and());
            }
            return left;
        }

        private Node and() {
            Node left = this.not();
            while (this.word("and")) {
                left = new Binary("and", left, this.not());
            }
            return left;
        }

        private Node not() {
            if (this.word("not")) {
                return new Unary("not", this.not());
            }
            return this.comparison();
        }

        private Node comparison() {
            Node left = this.sum();
            for (String op : new String[] {"==", "!=", ">=", "<=", ">", "<"}) {
                if (this.symbol(op)) {
                    return new Binary(op, left, this.sum());
                }
            }
            return left;
        }

        private Node sum() {
            Node left = this.product();
            while (true) {
                if (this.symbol("+")) {
                    left = new Binary("+", left, this.product());
                } else if (this.symbol("-")) {
                    left = new Binary("-", left, this.product());
                } else {
                    return left;
                }
            }
        }

        private Node product() {
            Node left = this.unary();
            while (true) {
                if (this.symbol("*")) {
                    left = new Binary("*", left, this.unary());
                } else if (this.symbol("/")) {
                    left = new Binary("/", left, this.unary());
                } else {
                    return left;
                }
            }
        }

        private Node unary() {
            if (this.symbol("-")) {
                return new Unary("-", this.unary());
            }
            return this.primary();
        }

        private Node primary() {
            this.skipSpace();
            if (this.at >= this.text.length()) {
                throw this.error("Expected a value");
            }
            char c = this.text.charAt(this.at);
            if (c == '(') {
                this.at++;
                Node inner = this.expression();
                this.expect(')');
                return inner;
            }
            if (c == '\'' || c == '"') {
                return new Literal(this.quoted(c));
            }
            if (c == '`') {
                String name = this.quoted('`');
                this.columns.add(name);
                return new Column(name);
            }
            if (Character.isDigit(c) || (c == '.' && this.at + 1 < this.text.length() && Character.isDigit(this.text.charAt(this.at + 1)))) {
                int start = this.at;
                while (this.at < this.text.length() && (Character.isDigit(this.text.charAt(this.at)) || this.text.charAt(this.at) == '.')) {
                    this.at++;
                }
                try {
                    return new Literal(Double.valueOf(this.text.substring(start, this.at)));
                } catch (NumberFormatException bad) {
                    throw this.error("Not a number: " + this.text.substring(start, this.at), start);
                }
            }
            if (Character.isLetter(c) || c == '_') {
                int start = this.at;
                while (this.at < this.text.length() && (Character.isLetterOrDigit(this.text.charAt(this.at)) || this.text.charAt(this.at) == '_')) {
                    this.at++;
                }
                String name = this.text.substring(start, this.at);
                String lower = name.toLowerCase();
                if ("true".equals(lower) || "false".equals(lower)) {
                    return new Literal(Boolean.valueOf(lower));
                }
                if ("null".equals(lower)) {
                    return new Literal(null);
                }
                this.skipSpace();
                if (this.at < this.text.length() && this.text.charAt(this.at) == '(') {
                    return this.call(lower, start);
                }
                this.columns.add(name);
                return new Column(name);
            }
            throw this.error("Unexpected '" + c + "'");
        }

        private Node call(String name, int start) {
            if (!FUNCTIONS.contains(name)) {
                throw new IllegalArgumentException("Unknown function " + name + "; use one of " + String.join(", ", FUNCTIONS) + ".");
            }
            this.at++;
            List<Node> args = new ArrayList<>();
            this.skipSpace();
            if (this.at < this.text.length() && this.text.charAt(this.at) == ')') {
                this.at++;
                if (!"today".equals(name)) {
                    throw new IllegalArgumentException(name + " takes at least 1 value.");
                }
                return new Call(name, args);
            } else {
                do {
                    args.add(this.expression());
                } while (this.symbol(","));
                this.expect(')');
            }
            arity(name, args.size());
            return new Call(name, args);
        }

        private static void arity(String name, int count) {
            switch (name) {
                case "if":
                    if (count != 3) {
                        throw new IllegalArgumentException("if takes 3 values: if(condition, then, otherwise).");
                    }
                    return;
                case "round":
                    if (count < 1 || count > 2) {
                        throw new IllegalArgumentException("round takes 1 or 2 values: round(value, places).");
                    }
                    return;
                case "regex_extract":
                    if (count != 2) {
                        throw new IllegalArgumentException("regex_extract takes 2 values: regex_extract(text, pattern).");
                    }
                    return;
                case "days_between":
                    if (count != 2) {
                        throw new IllegalArgumentException("days_between takes 2 dates: days_between(from, to).");
                    }
                    return;
                case "today":
                    if (count != 0) {
                        throw new IllegalArgumentException("today takes no values: today().");
                    }
                    return;
                case "mod":
                    if (count != 2) {
                        throw new IllegalArgumentException("mod takes 2 values: mod(value, divisor).");
                    }
                    return;
                case "date_add":
                    if (count < 2 || count > 3) {
                        throw new IllegalArgumentException("date_add takes 2 or 3 values: date_add(date, amount, unit).");
                    }
                    return;
                case "date_diff":
                    if (count < 2 || count > 3) {
                        throw new IllegalArgumentException("date_diff takes 2 or 3 values: date_diff(from, to, unit).");
                    }
                    return;
                case "date_trunc":
                    if (count != 2) {
                        throw new IllegalArgumentException("date_trunc takes 2 values: date_trunc(date, unit).");
                    }
                    return;
                case "json_get":
                    if (count != 2) {
                        throw new IllegalArgumentException("json_get takes 2 values: json_get(json, path).");
                    }
                    return;
                case "json_array_length": case "json_keys":
                    if (count < 1 || count > 2) {
                        throw new IllegalArgumentException(name + " takes 1 or 2 values: " + name + "(json, path).");
                    }
                    return;
                case "abs": case "is_null": case "number": case "text": case "floor": case "ceil":
                    if (count != 1) {
                        throw new IllegalArgumentException(name + " takes 1 value.");
                    }
                    return;
                default:
                    if (count < 1) {
                        throw new IllegalArgumentException(name + " takes at least 1 value.");
                    }
            }
        }

        private String quoted(char quote) {
            int start = this.at;
            this.at++;
            StringBuilder value = new StringBuilder();
            while (this.at < this.text.length() && this.text.charAt(this.at) != quote) {
                value.append(this.text.charAt(this.at++));
            }
            if (this.at >= this.text.length()) {
                throw this.error("Unclosed " + quote, start);
            }
            this.at++;
            return value.toString();
        }

        private boolean word(String keyword) {
            this.skipSpace();
            int end = this.at + keyword.length();
            if (end <= this.text.length() && this.text.substring(this.at, end).equalsIgnoreCase(keyword)
                && (end == this.text.length() || !(Character.isLetterOrDigit(this.text.charAt(end)) || this.text.charAt(end) == '_'))) {
                this.at = end;
                return true;
            }
            return false;
        }

        private boolean symbol(String op) {
            this.skipSpace();
            if (this.text.startsWith(op, this.at)) {
                // '=' alone is not an operator: ">" must not swallow the ">" of ">=".
                this.at += op.length();
                return true;
            }
            return false;
        }

        private void expect(char c) {
            this.skipSpace();
            if (this.at >= this.text.length() || this.text.charAt(this.at) != c) {
                throw this.error("expected '" + c + "'");
            }
            this.at++;
        }

        private void skipSpace() {
            while (this.at < this.text.length() && Character.isWhitespace(this.text.charAt(this.at))) {
                this.at++;
            }
        }

        private IllegalArgumentException error(String what) {
            return this.error(what, this.at);
        }

        private IllegalArgumentException error(String what, int where) {
            return new IllegalArgumentException(what + " at " + (where + 1) + " in: " + this.text);
        }
    }
}
