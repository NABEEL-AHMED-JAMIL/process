package process.analytics;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import process.analytics.canvas.FilterClause;
import process.analytics.canvas.FilterCompiler;
import process.analytics.dto.ColumnDto;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Whether a value a user typed can become part of the statement, and whether a field name can.
 *
 * <b>This is the adversarial file for 07's central rule</b> -- "do not construct raw SQL by string
 * concatenation with untrusted values" -- and it is organised around the two halves of that rule
 * needing two different mechanisms. Values are BOUND, so the tests below try to get one into the
 * text and assert that the text never changes and the parameter list grows by exactly one. Field
 * names cannot be bound, so the tests try to get an expression, a location, a closing quote and a
 * column belonging to another dataset into the identifier position, and assert a refusal.
 *
 * <b>The last section runs the compiled predicates on a real DuckDB.</b> Asserting that a quote is
 * in the parameter list and not in the SQL is the mechanism; asserting that "'; DROP TABLE sales;--"
 * comes back as zero matching rows with the table still standing is the property. The first can be
 * true while the second is false if the SQL is assembled elsewhere, so both are here.
 *
 * The positive controls matter as much as the attacks. A compiler that refused everything would
 * pass every adversarial case in this file, so all fourteen operators are exercised for what they
 * emit and what they bind, and several are executed to check the predicate means what it says --
 * including the two places where this class deliberately does NOT do what SQL does: not-equals and
 * not-in admit rows whose value is missing, because a row with no value is not that value.
 *
 * @author Nabeel Ahmed
 */
class FilterCompilerTest {

    /**
     * One dataset's schema, covering every type branch the coercion has.
     *
     * "odd name" and the column with a quote in it are not decoration: a CSV header is allowed to
     * contain both, DuckDB will read such a file, and they are what the quoting exists for.
     */
    private static final List<ColumnDto> SCHEMA = Arrays.asList(
        new ColumnDto("region", "VARCHAR"),
        new ColumnDto("city", "VARCHAR"),
        new ColumnDto("amount", "DOUBLE"),
        new ColumnDto("qty", "INTEGER"),
        new ColumnDto("price", "DECIMAL(18,2)"),
        new ColumnDto("booked_on", "DATE"),
        new ColumnDto("seen_at", "TIMESTAMP"),
        new ColumnDto("active", "BOOLEAN"),
        new ColumnDto("odd name", "VARCHAR"),
        new ColumnDto("we\"ird", "VARCHAR"));

    /** A day named, so that a relative window is a fact rather than a moving target. */
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    /** A real engine, for the section that asks what the predicates actually do. */
    private static Connection engine;

    @BeforeAll
    static void openEngine() throws Exception {
        engine = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement statement = engine.createStatement()) {
            statement.execute("CREATE TABLE sales (region VARCHAR, city VARCHAR, amount DOUBLE, "
                + "qty INTEGER, price DECIMAL(18,2), booked_on DATE, seen_at TIMESTAMP, "
                + "active BOOLEAN)");
            statement.execute("INSERT INTO sales VALUES "
                + "('north','oslo',10.5,1,9.99,DATE '2024-01-01',TIMESTAMP '2024-01-01 10:00:00',true),"
                + "('south','rome',21.0,2,19.99,DATE '2024-02-01',TIMESTAMP '2024-02-01 11:00:00',false),"
                + "(NULL,'lima',42.0,3,29.99,DATE '2024-03-01',TIMESTAMP '2024-03-01 12:00:00',true)");
        }
    }

    @AfterAll
    static void closeEngine() throws Exception {
        engine.close();
    }

    // ---- the fourteen operators, as positive controls -------------------------------------------

    @Test
    void equalsBindsItsValueAndNamesTheColumnFromTheSchema() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            FilterClause.of("region", FilterClause.Operator.EQ, "north"));

        assertThat(compiled.getSql()).isEqualTo("\"region\" = ?");
        assertThat(compiled.getParameters()).containsExactly("north");
    }

    @Test
    void notEqualsAdmitsRowsWithNoValueAtAll() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            FilterClause.of("region", FilterClause.Operator.NEQ, "north"));

        // IS DISTINCT FROM, deliberately, and not <>. The executed control below is the one that
        // matters: SQL's <> would drop the row whose region is null, and nobody picking "is not
        // north" off a menu means "and also hide the rows that do not say".
        assertThat(compiled.getSql()).isEqualTo("\"region\" IS DISTINCT FROM ?");
        assertThat(rowsMatching(compiled)).isEqualTo(2L);
    }

    @Test
    void containsAndStartsWithUseFunctionsRatherThanLike() throws Exception {
        assertThat(compile(FilterClause.of("city", FilterClause.Operator.CONTAINS, "om")).getSql())
            .isEqualTo("contains(\"city\", ?)");
        assertThat(compile(
            FilterClause.of("city", FilterClause.Operator.STARTS_WITH, "ro")).getSql())
            .isEqualTo("starts_with(\"city\", ?)");
    }

    @Test
    void greaterAndLessThanCoerceTheirBoundToTheColumnsType() throws Exception {
        AnalyticsEngine.BoundStatement greater = compile(
            FilterClause.of("amount", FilterClause.Operator.GT, "20"));

        assertThat(greater.getSql()).isEqualTo("\"amount\" > ?");
        // The bound is a BigDecimal and not the String "20". Measured on duckdb_jdbc 1.1.3, binding
        // a String against a DOUBLE column is "Binder Error: Cannot compare values of type DOUBLE
        // and type VARCHAR" -- so an untyped bind would have made every numeric filter an engine
        // error rather than a comparison.
        assertThat(greater.getParameters()).containsExactly(new BigDecimal("20"));
        assertThat(rowsMatching(greater)).isEqualTo(2L);

        assertThat(compile(FilterClause.of("qty", FilterClause.Operator.LT, "3")).getSql())
            .isEqualTo("\"qty\" < ?");
    }

    @Test
    void betweenTakesTwoValuesAndBoundsBothSides() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            values("price", FilterClause.Operator.BETWEEN, "10.00", "25.00"));

        assertThat(compiled.getSql()).isEqualTo("(\"price\" >= ? AND \"price\" <= ?)");
        assertThat(compiled.getParameters())
            .containsExactly(new BigDecimal("10.00"), new BigDecimal("25.00"));
        assertThat(rowsMatching(compiled)).isEqualTo(1L);
    }

    @Test
    void inAndNotInBindOnePlaceholderPerValue() throws Exception {
        AnalyticsEngine.BoundStatement in = compile(
            values("region", FilterClause.Operator.IN, "north", "south"));

        assertThat(in.getSql()).isEqualTo("\"region\" IN (?, ?)");
        assertThat(in.getParameters()).containsExactly("north", "south");
        assertThat(rowsMatching(in)).isEqualTo(2L);

        AnalyticsEngine.BoundStatement notIn = compile(
            values("region", FilterClause.Operator.NOT_IN, "north"));

        // The null branch is the same judgement not-equals makes, and the executed count is what
        // proves it: plain SQL NOT IN returns 1 here, because the null row's comparison is unknown.
        assertThat(notIn.getSql()).isEqualTo("(\"region\" IS NULL OR \"region\" NOT IN (?))");
        assertThat(rowsMatching(notIn)).isEqualTo(2L);
    }

    @Test
    void isNullAndIsNotNullBindNothingAtAll() throws Exception {
        AnalyticsEngine.BoundStatement isNull = compile(
            FilterClause.of("region", FilterClause.Operator.IS_NULL, null));

        assertThat(isNull.getSql()).isEqualTo("\"region\" IS NULL");
        assertThat(isNull.getParameters()).isEmpty();
        assertThat(rowsMatching(isNull)).isEqualTo(1L);

        assertThat(compile(FilterClause.of("region", FilterClause.Operator.IS_NOT_NULL, null))
            .getSql()).isEqualTo("\"region\" IS NOT NULL");
    }

    @Test
    void aDateRangeOnADateColumnIsInclusiveAtBothEnds() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            values("booked_on", FilterClause.Operator.DATE_RANGE, "2024-01-01", "2024-02-01"));

        assertThat(compiled.getSql()).isEqualTo("(\"booked_on\" >= ? AND \"booked_on\" <= ?)");
        assertThat(compiled.getParameters())
            .containsExactly(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1));
        assertThat(rowsMatching(compiled)).isEqualTo(2L);
    }

    @Test
    void aDateRangeOnATimestampColumnIncludesTheWholeOfTheLastDay() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            values("seen_at", FilterClause.Operator.DATE_RANGE, "2024-01-01", "2024-02-01"));

        // The defect this branch exists to prevent: seen_at <= DATE '2024-02-01' is midnight on the
        // 1st, so the 11:00 row on that day is missing and the report is quietly short by a day.
        // Half-open at the start of the following day is the only shape in which an inclusive day
        // range means the whole day.
        assertThat(compiled.getSql()).isEqualTo("(\"seen_at\" >= ? AND \"seen_at\" < ?)");
        assertThat(compiled.getParameters()).containsExactly(
            LocalDateTime.of(2024, 1, 1, 0, 0), LocalDateTime.of(2024, 2, 2, 0, 0));
        assertThat(rowsMatching(compiled)).isEqualTo(2L);
    }

    @Test
    void aRelativeDateResolvesAgainstTheCompilersOwnClockAndSaysWhatItUsed() throws Exception {
        FilterCompiler compiler = new FilterCompiler(FilterCompiler.Columns.of(SCHEMA), TODAY);
        AnalyticsEngine.BoundStatement compiled = compiler.compile(
            FilterClause.of("booked_on", FilterClause.Operator.RELATIVE_DATE, "LAST_7_DAYS"));

        // Absolute instants in the parameters and no current_date anywhere in the text: the same
        // statement run tomorrow answers the same question, which is what makes a saved analysis
        // and a history row mean anything.
        assertThat(compiled.getSql()).doesNotContain("current_date").doesNotContain("now(");
        assertThat(compiled.getParameters())
            .containsExactly(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 15));
        assertThat(compiler.getResolvedWindows())
            .containsEntry("LAST_7_DAYS", "2026-03-09 to 2026-03-15");
    }

    @Test
    void everyRelativeWindowTheVocabularyNamesResolves() throws Exception {
        for (String window : Arrays.asList("TODAY", "YESTERDAY", "LAST_7_DAYS", "LAST_30_DAYS",
            "LAST_90_DAYS", "THIS_MONTH", "LAST_MONTH", "THIS_YEAR", "LAST_YEAR",
            "LAST_N_DAYS:14")) {

            FilterCompiler compiler = new FilterCompiler(FilterCompiler.Columns.of(SCHEMA), TODAY);
            assertThat(compiler.compile(FilterClause.of("booked_on",
                FilterClause.Operator.RELATIVE_DATE, window)).getParameters())
                .as(window).hasSize(2);
        }
        // And the closed vocabulary is closed. An expression language here would be fourteen
        // boundary decisions made by whoever typed the string.
        assertThat(refusedBy(FilterClause.of("booked_on", FilterClause.Operator.RELATIVE_DATE,
            "LAST_7_MOONS"))).contains("not a relative date");
    }

    @Test
    void aNumericRangeRefusesAColumnThatHoldsNoNumbers() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            values("amount", FilterClause.Operator.NUMERIC_RANGE, "10", "30"));

        assertThat(compiled.getSql()).isEqualTo("(\"amount\" >= ? AND \"amount\" <= ?)");
        assertThat(rowsMatching(compiled)).isEqualTo(2L);

        // This is the difference between NUMERIC_RANGE and BETWEEN, and the reason 07 lists both.
        assertThat(refusedBy(values("region", FilterClause.Operator.NUMERIC_RANGE, "10", "30")))
            .contains("numeric range needs a numeric column");
    }

    @Test
    void nestedGroupsCompileToNestedBrackets() throws Exception {
        FilterClause tree = FilterClause.group(FilterClause.LogicalOp.AND, Arrays.asList(
            FilterClause.of("region", FilterClause.Operator.EQ, "north"),
            FilterClause.group(FilterClause.LogicalOp.OR, Arrays.asList(
                FilterClause.of("city", FilterClause.Operator.EQ, "oslo"),
                FilterClause.of("qty", FilterClause.Operator.GT, "1")))));

        AnalyticsEngine.BoundStatement compiled = compile(tree);

        assertThat(compiled.getSql())
            .isEqualTo("(\"region\" = ? AND (\"city\" = ? OR \"qty\" > ?))");
        assertThat(compiled.getParameters())
            .containsExactly("north", "oslo", new BigDecimal("1"));
        assertThat(rowsMatching(compiled)).isEqualTo(1L);
    }

    @Test
    void anAbsentFilterIsNoWhereClauseRatherThanATautology() throws Exception {
        assertThat(new FilterCompiler(FilterCompiler.Columns.of(SCHEMA), TODAY).compile(null))
            .isNull();
    }

    // ---- getting a value into the statement -----------------------------------------------------

    @Test
    void aQuoteInAValueEndsUpInTheParameterListAndNotInTheSql() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            FilterClause.of("region", FilterClause.Operator.EQ, "no'rth"));

        assertThat(compiled.getSql()).isEqualTo("\"region\" = ?");
        assertThat(compiled.getSql()).doesNotContain("'");
        assertThat(compiled.getParameters()).containsExactly("no'rth");
    }

    @Test
    void everyClassicInjectionIsOneParameterAndChangesNothingAboutTheStatement() throws Exception {
        List<String> attacks = Arrays.asList(
            "north'; DROP TABLE sales; --",
            "' OR '1'='1",
            "north' UNION SELECT 1,2,3 --",
            "north') OR (SELECT count(*) FROM sales) > 0 --",
            "north /* comment */",
            "north\\'; DELETE FROM sales; --",
            "north ; DROP TABLE sales",
            "'; ATTACH 's3://victim/x.db'; --");

        for (String attack : attacks) {
            AnalyticsEngine.BoundStatement compiled = compile(
                FilterClause.of("region", FilterClause.Operator.EQ, attack));

            // The statement is the same eleven characters whatever was typed. That is the whole
            // mechanism: there is no escaping anywhere in the compiler, because a value never
            // reaches the text to need any.
            assertThat(compiled.getSql()).as(attack).isEqualTo("\"region\" = ?");
            assertThat(compiled.getParameters()).as(attack).containsExactly(attack);
        }
    }

    @Test
    void aValueThatLooksLikeSqlMatchesNothingAndLeavesTheTableStanding() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            FilterClause.of("region", FilterClause.Operator.EQ, "north'; DROP TABLE sales; --"));

        // The property, as opposed to the mechanism. The predicate runs, matches no row because no
        // region is spelled that way, and the table is still there afterwards.
        assertThat(rowsMatching(compiled)).isZero();
        assertThat(rowsMatching(compile(
            FilterClause.of("region", FilterClause.Operator.EQ, "north")))).isEqualTo(1L);
    }

    @Test
    void likeMetacharactersInAContainsAreLiteralAndNotWildcards() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            FilterClause.of("city", FilterClause.Operator.CONTAINS, "%"));

        // The reason contains() is used rather than LIKE '%' || ? || '%'. Under LIKE this would
        // have matched every row; under contains() it matches the rows whose city has a per cent
        // sign in it, which is none of them. The usual fix for that -- escaping the user's % and _
        // on the way in -- is exactly the escaping this class does not do anywhere.
        assertThat(rowsMatching(compiled)).isZero();
        assertThat(rowsMatching(compile(
            FilterClause.of("city", FilterClause.Operator.CONTAINS, "om")))).isEqualTo(1L);
    }

    @Test
    void aValueWithAThousandCharactersIsStillOneParameter() throws Exception {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            huge.append("');--");
        }
        AnalyticsEngine.BoundStatement compiled = compile(
            FilterClause.of("region", FilterClause.Operator.EQ, huge.toString()));

        assertThat(compiled.getSql()).isEqualTo("\"region\" = ?");
        assertThat(compiled.getParameters()).hasSize(1);
    }

    // ---- getting a field name into the statement ------------------------------------------------

    @Test
    void aFieldThatIsReallyAnExpressionIsNotAColumnAndIsRefused() throws Exception {
        List<String> attacks = Arrays.asList(
            "amount) FROM sales; DROP TABLE sales; --",
            "amount\" , (SELECT 1) AS \"x",
            "region\" = 'x' OR \"1\" = \"1",
            "1=1",
            "count(*)",
            "region--",
            "region/*",
            "*");

        for (String attack : attacks) {
            // The identifier position is the one place a request could contribute characters to the
            // SQL, and this is the whole defence: the name is looked UP, and a name that is not in
            // the DESCRIBE is not a name. There is nothing here to escape correctly.
            assertThat(refusedBy(FilterClause.of(attack, FilterClause.Operator.EQ, "x")))
                .as(attack).contains("no column called");
        }
    }

    @Test
    void aFieldThatIsALocationIsRefusedTheSameWayAsAMisspelling() throws Exception {
        // Phase three's exploit was a quoted SCHEMA carrying an s3:// URL past a check that only
        // looked at table_name. The equivalent shape in this layer is a field name, and the answer
        // is not a better pattern -- it is that the allow-list is the file's own column list, so a
        // URL is simply not in it.
        for (String attack : Arrays.asList("s3://victim/payroll.csv", "azure://victim/x.parquet",
            "http://169.254.169.254/latest/meta-data/", "/etc/passwd", "../../secrets.csv")) {

            assertThat(refusedBy(FilterClause.of(attack, FilterClause.Operator.EQ, "x")))
                .as(attack).contains("no column called");
        }
    }

    @Test
    void aColumnOfSomeOtherDatasetIsNotAColumnOfThisOne() throws Exception {
        // The cross-dataset case stated on its own, because it is the one an attacker would try
        // second: a name that is a perfectly ordinary identifier and simply belongs to a different
        // file. Nothing about it looks dangerous, and it is refused for the same reason as "1=1".
        assertThat(refusedBy(FilterClause.of("salary", FilterClause.Operator.GT, "100000")))
            .contains("no column called \"salary\"");
    }

    @Test
    void theNameWrittenIntoTheSqlIsTheSchemasSpellingAndNeverTheRequests() throws Exception {
        AnalyticsEngine.BoundStatement compiled = compile(
            FilterClause.of("ReGiOn", FilterClause.Operator.EQ, "north"));

        // The fold happens on the way IN. What comes out is the string the DESCRIBE returned, so a
        // request cannot contribute even the CASE of an identifier to the statement.
        assertThat(compiled.getSql()).isEqualTo("\"region\" = ?");
    }

    @Test
    void twoColumnsDifferingOnlyInCaseKeepTheirExactNamesAndLoseTheFold() throws Exception {
        List<ColumnDto> clashing = Arrays.asList(
            new ColumnDto("Region", "VARCHAR"), new ColumnDto("region", "VARCHAR"));
        FilterCompiler compiler = new FilterCompiler(FilterCompiler.Columns.of(clashing), TODAY);

        // Both exact spellings still resolve, and to different columns.
        assertThat(compiler.compile(FilterClause.of("Region", FilterClause.Operator.EQ, "x"))
            .getSql()).isEqualTo("\"Region\" = ?");
        assertThat(compiler.compile(FilterClause.of("region", FilterClause.Operator.EQ, "x"))
            .getSql()).isEqualTo("\"region\" = ?");
        // The convenience is withdrawn only for the one ambiguous name: picking silently would mean
        // filtering a column the user did not choose.
        assertThat(refusedBy(clashing, FilterClause.of("REGION", FilterClause.Operator.EQ, "x")))
            .contains("no column called");
    }

    @Test
    void aColumnWhoseOwnNameHasAQuoteInItIsUsableAndCorrectlyQuoted() throws Exception {
        // Not an attack: a CSV header is allowed to contain a double quote and DuckDB reads such a
        // file. The doubling is what makes the column usable at all -- the safety is that the name
        // came out of the schema in the first place.
        assertThat(compile(FilterClause.of("we\"ird", FilterClause.Operator.EQ, "x")).getSql())
            .isEqualTo("\"we\"\"ird\" = ?");
        assertThat(compile(FilterClause.of("odd name", FilterClause.Operator.EQ, "x")).getSql())
            .isEqualTo("\"odd name\" = ?");
    }

    @Test
    void aDatasetWhoseOwnHeaderCarriesANulByteIsRefusedRatherThanQuoted() {
        // The one check made against the SCHEMA rather than against the request. A statement is
        // text, and text with a NUL in it is two different texts: measured on 1.1.3, DuckDB's
        // parser and its executor both stop at the byte, which is the property StatementGate's own
        // NUL rule rests on. Here the byte would arrive inside an identifier about to be quoted, so
        // it would truncate the composed statement. Both layers truncate in the same place today,
        // so this fails closed either way -- refusing it means nobody has to re-derive that the day
        // one of them changes.
        List<ColumnDto> poisoned = Collections.singletonList(
            new ColumnDto("reg\0ion", "VARCHAR"));

        AnalyticsException refused = catchThrowableOfType(
            () -> FilterCompiler.Columns.of(poisoned), AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("a character in its name that a query cannot");
    }

    @Test
    void aRejectedFieldNameComesBackStrippedOfEverythingButANameShape() throws Exception {
        String message = refusedBy(FilterClause.of(
            "<img src=x onerror=alert(1)>", FilterClause.Operator.EQ, "x"));

        // The name is echoed so a user can see what they asked for, and the message travels through
        // a JSON envelope into somebody else's page. Nothing that is not a plain name survives.
        assertThat(message).doesNotContain("<").doesNotContain(">").doesNotContain("=");
        assertThat(message).contains("?img src?x onerror?alert?1??");
    }

    // ---- the bounds that stop a request being a denial of service -------------------------------

    @Test
    void aFilterNestedPastTheCeilingIsRefusedRatherThanWalked() throws Exception {
        FilterClause deep = FilterClause.of("region", FilterClause.Operator.EQ, "north");
        for (int i = 0; i < 40; i++) {
            deep = FilterClause.group(FilterClause.LogicalOp.AND, Collections.singletonList(deep));
        }
        // The walk is recursive and the tree comes from a request body. A StackOverflowError is an
        // Error rather than an Exception and would go straight past the endpoint's catch blocks.
        assertThat(refusedBy(deep)).contains("nested too deeply");
    }

    @Test
    void aFilterWithMoreConditionsThanTheCeilingIsRefused() throws Exception {
        List<FilterClause> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            many.add(FilterClause.of("region", FilterClause.Operator.EQ, "north" + i));
        }
        assertThat(refusedBy(FilterClause.group(FilterClause.LogicalOp.OR, many)))
            .contains("up to 200 filter conditions");
    }

    @Test
    void anInListLongerThanTheCeilingIsRefused() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add("v" + i);
        }
        FilterClause clause = FilterClause.of("region", FilterClause.Operator.IN, null);
        clause.setValues(many);
        assertThat(refusedBy(clause)).contains("up to 500 values");
    }

    @Test
    void anEmptyInListIsARefusalRatherThanAGuess() throws Exception {
        FilterClause clause = FilterClause.of("region", FilterClause.Operator.IN, null);
        clause.setValues(Collections.emptyList());

        // "IN ()" is a syntax error, and both readings of an empty list -- match nothing, match
        // everything -- are answers to a question the caller did not ask.
        assertThat(refusedBy(clause)).contains("needs at least one value");
    }

    @Test
    void aNullInsideAnInListIsRefusedBecauseItWouldEmptyTheResult() throws Exception {
        FilterClause clause = FilterClause.of("region", FilterClause.Operator.IN, null);
        clause.setValues(Arrays.asList("north", null));

        // "x IN (NULL)" is unknown for every row, so one null quietly turns a filter into a filter
        // that matches nothing. IS_NULL is the operator for that question.
        assertThat(refusedBy(clause)).contains("cannot contain an empty value");
    }

    @Test
    void anEmptyGroupIsRefusedRatherThanDropped() throws Exception {
        FilterClause tree = FilterClause.group(FilterClause.LogicalOp.OR, Collections.emptyList());

        // Dropping it would change the answer without saying so: an empty OR branch read as "no
        // constraint" widens the result rather than narrowing it.
        assertThat(refusedBy(tree)).contains("at least one condition");
    }

    // ---- values that are not what the column holds ----------------------------------------------

    @Test
    void aThresholdThatIsNotANumberOnANumericColumnIsASentenceNotAnEngineError() throws Exception {
        assertThat(refusedBy(FilterClause.of("amount", FilterClause.Operator.GT, "twenty")))
            .contains("holds numbers").contains("twenty");
    }

    @Test
    void aDateThatIsNotADateIsASentenceToo() throws Exception {
        assertThat(refusedBy(FilterClause.of("booked_on", FilterClause.Operator.EQ, "31/01/2024")))
            .contains("holds dates").contains("2024-01-31");
    }

    @Test
    void aBooleanColumnTakesOnlyTrueOrFalse() throws Exception {
        assertThat(compile(FilterClause.of("active", FilterClause.Operator.EQ, "true"))
            .getParameters()).containsExactly(Boolean.TRUE);
        assertThat(refusedBy(FilterClause.of("active", FilterClause.Operator.EQ, "yes")))
            .contains("is true or false");
    }

    @Test
    void containsIsRefusedOnAColumnThatHoldsNoText() throws Exception {
        // Left to the engine this is "Binder Error: No function matches the given name and argument
        // types 'contains(DOUBLE, VARCHAR)'" followed by a list of candidate signatures.
        assertThat(refusedBy(FilterClause.of("amount", FilterClause.Operator.CONTAINS, "2")))
            .contains("looks inside text");
    }

    @Test
    void anOperatorThatNeedsAValueAndHasNoneIsRefused() throws Exception {
        assertThat(refusedBy(FilterClause.of("region", FilterClause.Operator.EQ, null)))
            .contains("needs a value");
        assertThat(refusedBy(values("price", FilterClause.Operator.BETWEEN, "10")))
            .contains("exactly two values");
        assertThat(refusedBy(FilterClause.of("region", null, "x")))
            .contains("what it is testing");
    }

    @Test
    void aDateRangeThatEndsBeforeItStartsIsRefused() throws Exception {
        assertThat(refusedBy(values("booked_on", FilterClause.Operator.DATE_RANGE,
            "2024-03-01", "2024-01-01"))).contains("ends before it starts");
    }

    @Test
    void aDateFilterOnAColumnThatHoldsNoDatesIsRefused() throws Exception {
        assertThat(refusedBy(values("region", FilterClause.Operator.DATE_RANGE,
            "2024-01-01", "2024-02-01"))).contains("date filter needs a date column");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static AnalyticsEngine.BoundStatement compile(FilterClause clause) throws Exception {
        return new FilterCompiler(FilterCompiler.Columns.of(SCHEMA), TODAY).compile(clause);
    }

    private static String refusedBy(FilterClause clause) {
        return refusedBy(SCHEMA, clause);
    }

    private static String refusedBy(List<ColumnDto> schema, FilterClause clause) {
        AnalyticsException refused = catchThrowableOfType(
            () -> new FilterCompiler(FilterCompiler.Columns.of(schema), TODAY).compile(clause),
            AnalyticsException.class);
        assertThat(refused).as("the compiler was expected to refuse this clause").isNotNull();
        return refused.getMessage();
    }

    /**
     * How many rows of the fixture the compiled predicate actually matches.
     *
     * The predicate is put behind a WHERE on a real table and the parameters are bound exactly as
     * DuckDbAnalyticsEngine binds them. This is what turns "the value is in the parameter list"
     * into "the value cannot do anything", which are two different claims.
     */
    private static long rowsMatching(AnalyticsEngine.BoundStatement compiled) throws SQLException {
        try (PreparedStatement prepared = engine.prepareStatement(
            "SELECT count(*) FROM sales WHERE " + compiled.getSql())) {

            List<Object> parameters = compiled.getParameters();
            for (int i = 0; i < parameters.size(); i++) {
                prepared.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet counted = prepared.executeQuery()) {
                return counted.next() ? counted.getLong(1) : -1L;
            }
        }
    }

    private static FilterClause values(String field, FilterClause.Operator operator,
        String... operands) {
        FilterClause clause = FilterClause.of(field, operator, null);
        clause.setValues(Arrays.asList(operands));
        return clause;
    }
}
