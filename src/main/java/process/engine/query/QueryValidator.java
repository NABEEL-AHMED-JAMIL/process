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
 * @author Nabeel Ahmed
 * */
@Component
public class QueryValidator {

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

        return ValidationResult.valid(statement.toString());
    }

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

            return this.firstNonSelectReason(((WithItem) selectBody).getSubSelect().getSelectBody());
        }

        return "Unsupported query construct: " + selectBody.getClass().getSimpleName();
    }

    private String shortMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ex.getClass().getSimpleName();
        }

        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }

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

        public String getNormalizedSql() {
            return this.normalizedSql;
        }
    }

}
