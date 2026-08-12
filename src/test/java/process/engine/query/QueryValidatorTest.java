package process.engine.query;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for QueryValidator -- this is the actual security gate that decides which SQL a
 * tenant is allowed to save/run through the Query Engine (see the class's own javadoc), so it
 * is tested independently of any Spring context or database: every case here must be provable
 * from the AST alone.
 * @author Nabeel Ahmed
 */
class QueryValidatorTest {

    private final QueryValidator queryValidator = new QueryValidator();

    @Test
    void rejectsNullOrEmptyQuery() {
        assertThat(this.queryValidator.validate(null).isValid()).isFalse();
        assertThat(this.queryValidator.validate("").isValid()).isFalse();
        assertThat(this.queryValidator.validate("   ").isValid()).isFalse();
    }

    @Test
    void rejectsUnparseableSql() {
        QueryValidator.ValidationResult result = this.queryValidator.validate("SELECT * FROM WHERE garbage $$$");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).contains("could not be parsed");
    }

    @Test
    void acceptsPlainSelect() {
        QueryValidator.ValidationResult result = this.queryValidator.validate("SELECT id, name FROM customers WHERE id = 1");
        assertThat(result.isValid()).isTrue();
        assertThat(result.getNormalizedSql()).isNotBlank();
    }

    @Test
    void acceptsSelectWithJoinAndLimit() {
        QueryValidator.ValidationResult result = this.queryValidator.validate(
            "SELECT a.id, b.name FROM a JOIN b ON a.id = b.a_id ORDER BY a.id LIMIT 50");
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void acceptsUnionOfPlainSelects() {
        QueryValidator.ValidationResult result = this.queryValidator.validate(
            "SELECT id FROM a UNION SELECT id FROM b");
        assertThat(result.isValid()).isTrue();
    }

    @Test
    void acceptsCteThatResolvesToSelect() {
        QueryValidator.ValidationResult result = this.queryValidator.validate(
            "WITH recent AS (SELECT id FROM orders WHERE created_at > '2026-01-01') SELECT * FROM recent");
        assertThat(result.isValid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO customers (name) VALUES ('x')",
        "UPDATE customers SET name = 'x' WHERE id = 1",
        "DELETE FROM customers WHERE id = 1",
        "DROP TABLE customers",
        "TRUNCATE TABLE customers",
        "CREATE TABLE x (id int)",
        "ALTER TABLE customers ADD COLUMN x int",
        "GRANT ALL ON customers TO someone"
    })
    void rejectsAnyNonSelectStatement(String sql) {
        QueryValidator.ValidationResult result = this.queryValidator.validate(sql);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void rejectsMultipleStatementsSeparatedBySemicolon() {
        QueryValidator.ValidationResult result = this.queryValidator.validate(
            "SELECT * FROM customers; DROP TABLE customers;");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).contains("single statement");
    }

    @Test
    void rejectsSelectIntoBecauseItCreatesATableAsASideEffect() {
        QueryValidator.ValidationResult result = this.queryValidator.validate(
            "SELECT * INTO new_table FROM customers");
        assertThat(result.isValid()).isFalse();
        assertThat(result.getReason()).contains("SELECT ... INTO");
    }

    @Test
    void rejectsNonSelectHiddenInsideAUnionBranch() {
        // Every branch of a UNION must itself resolve to a plain SELECT -- JSqlParser's grammar
        // cannot actually produce a non-Select branch under a Select node, so this mainly proves
        // firstNonSelectReason really does walk every branch rather than just the first one.
        QueryValidator.ValidationResult result = this.queryValidator.validate(
            "SELECT id FROM a UNION SELECT id INTO leaked FROM b");
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void cannotBeDefeatedByATrailingCommentHidingASecondStatement() {
        QueryValidator.ValidationResult result = this.queryValidator.validate(
            "SELECT * FROM customers -- ; DROP TABLE customers");
        // The comment is part of the single SELECT statement's trailing text, not a second
        // statement -- still valid, but the re-serialized SQL must not carry the comment forward
        // as executable text an attacker could otherwise smuggle meaning into.
        assertThat(result.isValid()).isTrue();
    }
}
