package process.pipeline.data;

import org.barco.platform.api.ApiTimes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

/**
 * A Compute formula (MIG-255): a small, closed language over one row's columns -- nothing in it can reach outside the row.
 *
 * <ul>
 *   <li>values: numbers (2, 0.785), text ('stable' or "stable"), true, false, null, and columns by name (length_cm, or
 *       `item length` in backticks for a name with spaces);</li>
 *   <li>arithmetic + - * / and a leading minus; comparisons == != &gt; &gt;= &lt; &lt;=; and, or, not; parentheses;</li>
 *   <li>functions: abs, coalesce, concat, days_between (ISO dates), if(condition, then, otherwise), is_null, max, min,
 *       number, regex_extract(text, pattern) (the first group of the first match, or the whole match; null when
 *       none), round(value[, places]), text, today() (the business date, YYYY-MM-DD, America/Chicago).</li>
 * </ul>
 *
 * A column read as CSV text is a number where it reads as one. An empty or null operand makes arithmetic null (never an
 * error), and dividing by zero is null. A comparison with null or empty is false; two numbers compare as numbers,
 * anything else as text (so ISO dates compare in date order). A text that is not a number, used as one, fails the row
 * and names its column.
 */
public final class Expression {

    static final List<String> FUNCTIONS = Collections.unmodifiableList(Arrays.asList("abs", "coalesce", "concat", "days_between", "if",
        "is_null", "max", "min", "number", "regex_extract", "round", "text", "today"));

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
                case "abs": case "is_null": case "number": case "text":
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
