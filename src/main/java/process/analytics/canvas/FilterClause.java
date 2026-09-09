package process.analytics.canvas;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One node of a filter tree: either a condition, or a group of them joined by AND or OR.
 *
 * <b>One class for both shapes rather than two, and the reason is Jackson rather than taste.</b>
 * 07 asks for "nested AND/OR groups", so the tree is recursive and the client sends whichever shape
 * fits at each level. A polymorphic pair would need a discriminator field on the wire that the
 * client has to remember to set correctly; here the shape IS the discriminator -- a node with
 * clauses is a group, a node with an operator is a condition -- and a node that is somehow both is
 * refused by {@link FilterCompiler} rather than resolved by a guess.
 *
 * <b>Every value is a String, including the numbers and the dates.</b> That is deliberate and it is
 * the field this type gets asked about most. JSON has one number type and it is a double, so a
 * client sending an eighteen-digit account id or a DECIMAL(38,2) threshold as a JSON number has
 * already lost it before the request is parsed; JSON has no date at all. Strings on the wire keep
 * the value exactly as the user typed it, and {@link FilterCompiler} turns each one into a value of
 * the COLUMN's own type -- so a threshold that is not a number on a numeric column is a refusal
 * with a sentence in it rather than a Binder Error from the engine.
 *
 * Nothing here validates. A request object that refused its own contents would have to duplicate
 * the schema check, which is the whole of the security argument and belongs in one place.
 *
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FilterClause {

    /** How the clauses of a group are joined. There is no NOT: a negating operator is used instead. */
    public enum LogicalOp {
        AND,
        OR
    }

    /**
     * The fourteen predicates 07 lists, and exactly those.
     *
     * Three of them look like duplicates of each other and are not: BETWEEN, NUMERIC_RANGE and
     * DATE_RANGE all produce a two-sided bound, and they differ in what the VALUES are allowed to
     * be. BETWEEN takes the column at its word and bounds it with two values of whatever type it
     * holds, so it works on text as well as on numbers; NUMERIC_RANGE refuses a bound that is not a
     * number and refuses a column that does not hold numbers; DATE_RANGE does the same for dates and
     * additionally knows that the last day of an inclusive range is a whole day on a TIMESTAMP
     * column. A filter builder that offered one operator for all three would have to guess which of
     * those a user meant from the characters they typed.
     *
     * RELATIVE_DATE is the one whose operand is not a value at all but a window -- LAST_7_DAYS and
     * its siblings -- resolved to two absolute instants before anything is bound. See
     * {@link FilterCompiler} for which clock resolves it and why the answer is reported back.
     */
    public enum Operator {
        EQ,
        NEQ,
        CONTAINS,
        STARTS_WITH,
        GT,
        LT,
        BETWEEN,
        IN,
        NOT_IN,
        IS_NULL,
        IS_NOT_NULL,
        DATE_RANGE,
        RELATIVE_DATE,
        NUMERIC_RANGE
    }

    /** AND or OR, on a group. Null on a condition. */
    private LogicalOp op;

    /** The children of a group, in the order they are joined. Null on a condition. */
    private List<FilterClause> clauses;

    /** The column this condition is about, named as the dataset names it. Null on a group. */
    private String field;

    /** What is being asked of the column. Null on a group. */
    private Operator operator;

    /** The single operand, for the ten operators that take one or none. */
    private String value;

    /** The operands, for IN and NOT_IN and for the three two-sided ranges. */
    private List<String> values;

    public FilterClause() {}

    /** A condition. */
    public static FilterClause of(String field, Operator operator, String value) {
        FilterClause clause = new FilterClause();
        clause.field = field;
        clause.operator = operator;
        clause.value = value;
        return clause;
    }

    /** A group. */
    public static FilterClause group(LogicalOp op, List<FilterClause> clauses) {
        FilterClause clause = new FilterClause();
        clause.op = op;
        clause.clauses = clauses;
        return clause;
    }

    /**
     * Whether this node joins other nodes rather than testing a column.
     *
     * Either half is enough to claim it, so that a group missing its op and a group missing its
     * clauses are both recognised AS groups and refused as malformed ones. Reading a node with
     * clauses but no op as a condition would send it down the operator path and produce a message
     * about a missing field, which is an answer to a question nobody asked.
     */
    @JsonIgnore
    public boolean isGroup() {
        return this.op != null || this.clauses != null;
    }

    public LogicalOp getOp() { return this.op; }
    public void setOp(LogicalOp op) { this.op = op; }

    public List<FilterClause> getClauses() { return this.clauses; }
    public void setClauses(List<FilterClause> clauses) { this.clauses = clauses; }

    public String getField() { return this.field; }
    public void setField(String field) { this.field = field; }

    public Operator getOperator() { return this.operator; }
    public void setOperator(Operator operator) { this.operator = operator; }

    public String getValue() { return this.value; }
    public void setValue(String value) { this.value = value; }

    public List<String> getValues() { return this.values; }
    public void setValues(List<String> values) { this.values = values; }
}
