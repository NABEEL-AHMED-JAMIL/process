package process.engine.query;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Read-only, single-statement enforcement for every query a tenant saves/runs through the Query
 * Engine -- this is the actual gate that makes tenant-supplied SQL safe to execute, so it is
 * deliberately NOT a string check like query.trim().toUpperCase().startsWith("SELECT")
 * (trivially defeated by "-- comment\nDROP TABLE x" or "SELECT 1; DROP TABLE x;"). It parses the
 * statement with a real SQL parser (JSqlParser) and inspects the resulting AST instead:
 *   - must parse as exactly ONE statement (multi-statement batches rejected outright)
 *   - that statement's root node must be a Select (INSERT/UPDATE/DELETE/DDL/TRUNCATE all rejected)
 *   - every branch of a UNION/INTERSECT/EXCEPT and every CTE (WITH ...) must also resolve to a
 *     plain SELECT -- JSqlParser's grammar already can't produce anything else under a Select
 *     node, so this is really just walking the tree to confirm that structurally, not a
 *     second independent check
 *   - a bare "SELECT ... INTO new_table" (creates a table as a side effect in some dialects) is
 *     rejected even though it parses as a Select, since PlainSelect.getIntoTables() is non-empty
 * This is one layer of defense, not the only one -- the design review's recommendation to also
 * run every query against a genuinely read-only database user/role stands regardless of what
 * this validator catches.
 * @author Nabeel Ahmed
 */
@Component
public class QueryValidator {

    /**
     * Method use to validate that sql is exactly one read-only SELECT statement
     * @param sql
     * @return ValidationResult
     * */
    public ValidationResult validate(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return ValidationResult.invalid("Query text is empty.");
        }
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (Exception ex) {
            return ValidationResult.invalid("Query could not be parsed as valid SQL: " + this.shortMessage(ex));
        }
        List<Statement> statementList = statements.getStatements();
        if (statementList == null || statementList.isEmpty()) {
            return ValidationResult.invalid("Query text is empty.");
        }
        if (statementList.size() > 1) {
            return ValidationResult.invalid("Only a single statement is allowed -- multiple statements (separated by \";\") are not supported.");
        }
        Statement statement = statementList.get(0);
        if (!(statement instanceof Select)) {
            return ValidationResult.invalid("Only SELECT queries are supported (this query engine is read-only). "
                + "Detected statement type: " + statement.getClass().getSimpleName());
        }
        String reason = this.firstNonSelectReason(((Select) statement).getSelectBody());
        if (reason != null) {
            return ValidationResult.invalid(reason);
        }
        // The re-serialized AST, not the raw input string -- guaranteed to be exactly one clean
        // statement with no trailing ";", comments, or stray whitespace, so callers (preview's
        // "wrap in a LIMIT subquery", execute) can safely embed it inside other SQL without
        // string-editing the user's original text themselves.
        return ValidationResult.valid(statement.toString());
    }

    /**
     * Method use to walk a SelectBody (handles plain SELECTs, UNION/INTERSECT/EXCEPT chains,
     * and WITH/CTE definitions) and return a rejection reason for the first non-read-only
     * construct found, or null if the whole tree is clean.
     * @param selectBody
     * @return String or null when clean
     * */
    private String firstNonSelectReason(SelectBody selectBody) {
        if (selectBody instanceof PlainSelect) {
            PlainSelect plainSelect = (PlainSelect) selectBody;
            if (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty()) {
                return "\"SELECT ... INTO\" is not supported -- it creates a table as a side effect, which this read-only query engine does not allow.";
            }
            return null;
        }
        if (selectBody instanceof SetOperationList) {
            for (SelectBody part : ((SetOperationList) selectBody).getSelects()) {
                String reason = this.firstNonSelectReason(part);
                if (reason != null) {
                    return reason;
                }
            }
            return null;
        }
        if (selectBody instanceof WithItem) {
            // jsqlparser 4.6's WithItem exposes its inner query as a SubSelect (getSubSelect()),
            // not a bare SelectBody -- getSelectBody() doesn't exist on this version's API.
            return this.firstNonSelectReason(((WithItem) selectBody).getSubSelect().getSelectBody());
        }
        // Any other SelectBody subtype JSqlParser introduces later that this switch doesn't
        // recognize yet -- fail closed (reject) rather than silently letting an unreviewed
        // construct through.
        return "Unsupported query construct: " + selectBody.getClass().getSimpleName();
    }

    private String shortMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ex.getClass().getSimpleName();
        }
        // JSqlParser's ParseException messages can run to many lines (full grammar expectation
        // dump) -- keep only the first line for a UI-friendly error.
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }

    /**
     * Simple valid/invalid + reason result -- kept as a small inner-ish value type rather than
     * overloading ResponseDto here, since QueryValidator is a domain/engine class with no
     * knowledge of the HTTP-layer response shape (see QueryDefinitionServiceImpl for where this
     * gets translated into one).
     * */
    public static final class ValidationResult {
        private final boolean valid;
        private final String reason;
        private final String normalizedSql;

        private ValidationResult(boolean valid, String reason, String normalizedSql) {
            this.valid = valid;
            this.reason = reason;
            this.normalizedSql = normalizedSql;
        }

        public static ValidationResult valid(String normalizedSql) {
            return new ValidationResult(true, null, normalizedSql);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason, null);
        }

        public boolean isValid() {
            return this.valid;
        }

        public String getReason() {
            return this.reason;
        }

        /** Only non-null when isValid() -- the re-serialized, single, clean statement (see
         * QueryValidator.validate's javadoc note above). */
        public String getNormalizedSql() {
            return this.normalizedSql;
        }
    }

}
