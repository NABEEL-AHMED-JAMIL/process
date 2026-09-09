package process.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a statement a person wrote is a read, decided by DuckDB's parser and not by a word list.
 *
 * The session is already locked down and the governor already bounds what a query may cost, so
 * this class answers the one question neither of those can. lock_configuration stops a session
 * being RECONFIGURED; it does not stop COPY ... TO, ATTACH, INSTALL, or a LOAD of an extension
 * that is already compiled in. disabled_filesystems removes the local target for most of that and
 * that defence is real -- but "the filesystem is gone" is a weaker claim than "we only run
 * SELECT", and this is where the stronger one is made.
 *
 * Why not a list of forbidden words. Every such list is a list of the writes somebody thought of,
 * and the ones nobody thought of are the interesting ones. It is also defeated by things that are
 * not clever: a leading comment, a newline, mixed case, or a semicolon -- while refusing
 * "SELECT ';DROP TABLE t'", which is a string constant and does nothing at all. A list that both
 * admits writes and refuses reads is not a weak version of this; it is a different, wrong answer.
 *
 * What is asked instead. DuckDB is made to parse the statement before it is allowed to run one:
 * json_serialize_sql serialises a set of semicolon-separated statements to JSON and will
 * serialise nothing but SELECTs, so one call answers everything this gate asks of the text -- did
 * it parse, how many statements is it, if it will not serialise was that because the text is
 * malformed (error_type "parser") or because the statement is not a read (error_type "not
 * implemented"), and what does the statement propose to read. The comments, the whitespace, the
 * case folding and the string literals are then DuckDB's problem, handled by the same parser that
 * would have run the thing.
 *
 * The text being judged is a bound PARAMETER, never concatenated into the probe. The statement
 * under suspicion is data to the statement doing the suspecting, which is the only arrangement in
 * which asking an engine about SQL is not itself an injection.
 *
 * There is a second rule here that is not about reads at all, and it is the one that is easy to
 * miss: a read can still be a read of the wrong thing. The session carries one connection's
 * credentials and DuckDB will spend them on any location a statement names, so a query that reads
 * "s3://someone-elses-bucket/x.csv" is both a read and precisely the request DatasetResolver was
 * built to make unaskable. readsOnlyWhatItWasGiven is that rule; without it, user-written SQL
 * would have handed back the field the API spent phase one removing.
 *
 * Why the statement COUNT is not a nicety. Measured on DuckDB 1.1.3:
 * executeQuery("SELECT 1; INSERT INTO t VALUES (77)") throws "executeQuery() can only be used
 * with queries that return a ResultSet" -- and the row is inserted anyway. The exception is a
 * complaint about the return value, not a refusal, and the write has already happened by the time
 * it is thrown. Anything but exactly one statement is refused here for that reason.
 *
 * What it refuses that somebody may reasonably want, so that the next person does not have to
 * find out by experiment: PIVOT and UNPIVOT, which are reads that DuckDB declines to serialise, so
 * they arrive here indistinguishable from a write; EXPLAIN in its parenthesised option form,
 * EXPLAIN (FORMAT JSON) ...; and every table function, range() and generate_series() included.
 * Each of those is a "not yet" rather than a "no" -- the first two need DuckDB to grow a way to
 * tell us what they are, and the third is one named entry away whenever somebody makes the case.
 *
 * This runs on the session that is about to run the query, so there is no second connection
 * anywhere -- which is the rule the whole module is shaped around.
 *
 * @author Nabeel Ahmed
 */
public final class StatementGate {

    /**
     * Everything the gate needs to know about a statement, from one parse and without running it.
     *
     * A subquery rather than a call per question, so the text is serialised once.
     * json_array_length is null when the parse failed and both error fields are null when it did
     * not, so the first four columns together say which outcome this is; the fifth is the parsed
     * statement itself, for the question a count cannot answer -- what it proposes to read.
     */
    private static final String PARSE_PROBE =
        "SELECT parsed.tree ->> '$.error' AS failed, "
        + "parsed.tree ->> '$.error_type' AS error_type, "
        + "parsed.tree ->> '$.error_message' AS message, "
        + "json_array_length(parsed.tree -> '$.statements') AS statements, "
        + "CAST(parsed.tree AS VARCHAR) AS tree "
        + "FROM (SELECT json_serialize_sql(CAST(? AS VARCHAR)) AS tree) AS parsed";

    /** DuckDB's own name for "this text is malformed", as opposed to "this is not a SELECT". */
    private static final String PARSE_FAILURE = "parser";

    /**
     * The one non-SELECT shape admitted, matched at the very start of the statement.
     *
     * Deliberately not tolerant of a comment in front of it. What runs for an admitted EXPLAIN is
     * this class's own "EXPLAIN " plus a remainder DuckDB has confirmed is a single read, so
     * anything the pattern skipped would be silently dropped from the statement the user wrote --
     * and dropping whitespace is the only version of that which is honest.
     */
    private static final Pattern EXPLAINED =
        Pattern.compile("^EXPLAIN\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** ANALYZE turns EXPLAIN from a plan into a run, so it is refused by name rather than by parse. */
    private static final Pattern ANALYZED =
        Pattern.compile("^ANALYZE\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** The same words bounded() uses, because an empty editor is the same event either way. */
    private static final String NOTHING_TO_RUN = "There is no query to run.";

    private static final String ONE_AT_A_TIME =
        "Analytics Studio runs one statement at a time. Remove the semicolon and everything after it.";

    private static final String READS_ONLY =
        "Analytics Studio only runs queries that read. This statement asks for something else, "
        + "so it was not run.";

    private static final String EXPLAIN_ONLY =
        "EXPLAIN ANALYZE runs the query as well as planning it. Run the query itself, or EXPLAIN "
        + "it without ANALYZE.";

    private static final String CANNOT_BE_BOUNDED =
        "The row limit could not be applied to this query. A comment on the last line is the usual "
        + "cause -- the limit is added after it, and ends up inside the comment.";

    private static final String BY_NAME_ONLY =
        "A query reads the datasets it was given, by name: dataset, and dataset2 when a second one "
        + "was chosen. It cannot name a file, a bucket or a URL of its own.";

    private static final String NO_TABLE_FUNCTIONS =
        "A query reads the datasets it was given, by name. Reading through a table function is not "
        + "allowed, because that is how a statement names a location nobody checked.";

    /**
     * What a FROM may name: something with a name, and nothing with a location in it.
     *
     * The characters excluded are the ones the two shapes that matter are made of --
     * "s3://bucket/key" by the colon and the slashes, "/etc/passwd" and "exports/*.csv" by the
     * slash and the star. Deliberately generous about the rest: a CTE can be called almost
     * anything, and refusing a name with an accent in it would be a bug that looks like security.
     *
     * It admits one thing knowingly: a bare relative filename, "FROM 'sales.csv'", which has no
     * slash to catch it by. That names a file next to the process rather than in a bucket, and
     * there is no local filesystem left for it to be found on -- measured, it comes back
     * "Permission Error: File system LocalFileSystem has been disabled by configuration". It is
     * the one place the two layers deliberately overlap, and the layer underneath is the one with
     * the stronger claim.
     */
    private static final Pattern JUST_A_NAME = Pattern.compile("[^/\\\\:*?\"'<>|]*");

    /**
     * Every identifier DuckDB will fold back into one location.
     *
     * A base table can be written catalog.schema.table, and the replacement scan joins whatever it
     * is given. Any one of the three is therefore somewhere a path can hide, so the same rule has
     * to hold for all three rather than for the part that happens to be last.
     */
    private static final String[] NAME_PARTS = { "table_name", "schema_name", "catalog_name" };

    /**
     * A statement is text, and text with a NUL in it is two different texts.
     *
     * Measured on 1.1.3, the parser and the executor agree: both stop at the NUL, so
     * "SELECT 1<NUL>; DROP TABLE customers" parses as, and runs as, "SELECT 1". That agreement is
     * what makes the gate safe, and it is also a property of a native boundary nobody here
     * controls. Refusing the byte outright costs a person nothing -- no keyboard produces one --
     * and means the gate never has to rely on two layers truncating in the same place.
     */
    private static final String NO_NUL_BYTE =
        "That statement contains a character a query cannot contain. Retype it, or paste it again "
        + "from a plain-text editor.";

    private static final String TOO_DEEP =
        "That query is nested too deeply to be checked. Break it into fewer levels of subquery.";

    /**
     * How deep a serialised statement may be before it is refused rather than walked.
     *
     * The walk below is recursive and the tree comes from a request. DuckDB's parser has limits of
     * its own, so this is not the first thing standing between a nested query and a stack, but a
     * StackOverflowError is an Error rather than an Exception and would leave the endpoint's catch
     * blocks unused. Far above any query a person writes: measured on 1.1.3, forty nested
     * subqueries serialise to a depth of 128 and a thirty-way join to 36, so this leaves room for
     * something like a hundred and thirty levels of subquery before anyone is refused for it.
     */
    private static final int MAX_TREE_DEPTH = 400;

    private static final ObjectMapper JSON = new ObjectMapper();

    private StatementGate() {}

    /**
     * The statement this session is allowed to run, or an exception saying why there isn't one.
     *
     * A refusal that is the user's to fix arrives as an AnalyticsException, already written for
     * them. A statement that does not PARSE arrives as the SQLException DuckDB would have thrown
     * had the statement run, carrying DuckDB's own words -- because once the user holds the SQL,
     * "syntax error at or near SELCT" says more than any sentence written here, and because
     * AnalyticsQueryService.explain() is already the one place that decides how an engine message
     * reaches a person. Rebuilding that decision here would be a second answer to the same
     * question, and the two would drift.
     *
     * @param session the locked-down session the statement will run on, used for its parser only
     * @param sql the text the user wrote
     * @param timeoutSeconds the same ceiling the query itself runs under
     */
    public static Admitted admit(Connection session, String sql, int timeoutSeconds)
        throws AnalyticsException, SQLException {

        String statement = sql == null ? "" : sql.trim();
        if (statement.isEmpty()) {
            throw new AnalyticsException(NOTHING_TO_RUN);
        }
        if (statement.indexOf('\0') >= 0) {
            throw new AnalyticsException(NO_NUL_BYTE);
        }

        Parse parse = parse(session, statement, timeoutSeconds);
        if (parse.isOneReadStatement()) {
            readsOnlyWhatItWasGiven(parse.getTree());
            // The text is executed exactly as written. Nothing here rewrites a user's query: a
            // gate that admits one statement and runs a different one has proved nothing.
            return new Admitted(statement, true);
        }
        if (parse.isNothing()) {
            // Parsed to no statements at all, which is what a file of comments looks like.
            throw new AnalyticsException(NOTHING_TO_RUN);
        }
        if (parse.isSeveralStatements()) {
            throw new AnalyticsException(ONE_AT_A_TIME);
        }
        if (parse.isMalformed()) {
            throw parse.asTheEngineWouldHaveThrownIt();
        }
        return explained(session, statement, timeoutSeconds);
    }

    /**
     * The same parser applied a second time, to the text that is actually about to run.
     *
     * admit() judges what the user submitted; bounded() then wraps it, and this asserts that the
     * wrapping did not turn one statement into two. It cannot today -- a text that closed the
     * wrapper's bracket early would have failed to parse on its own and never reached here -- and
     * that is exactly the kind of "cannot" that stops being true when somebody edits the wrapper.
     * Checking the composed string costs one parse and removes the argument.
     *
     * It also catches the one honest query the wrapper breaks: a comment on the last line, which
     * swallows the closing bracket and the LIMIT with it. Without this the user would meet a
     * syntax error about a statement they did not write.
     */
    public static void confirmComposed(Connection session, String executable, int timeoutSeconds)
        throws AnalyticsException, SQLException {

        Parse parse = parse(session, executable, timeoutSeconds);
        if (!parse.isOneReadStatement()) {
            throw new AnalyticsException(CANNOT_BE_BOUNDED);
        }
        readsOnlyWhatItWasGiven(parse.getTree());
    }

    /**
     * The rule that keeps DatasetResolver the only thing in this module that names a location.
     *
     * This is the part of the gate that is NOT about reads and writes, and it closes a hole the
     * rest of it cannot see. A session carries one storage connection's credentials, and DuckDB
     * will use them for any s3:// URL a statement names -- so "SELECT * FROM
     * read_csv_auto('s3://someone-elses-bucket/x.csv')" is a perfectly good READ, and it is
     * exactly the request DatasetResolver exists to make unaskable. Synthesis 3.3 puts it as
     * "read a different bucket with these credentials is not a request this API can express";
     * user-written SQL is a field to put it in, and this is what takes the field away again.
     *
     * Two shapes carry a location into a query and this refuses both, on the PARSED statement
     * rather than on its text:
     *
     *   * a table function -- read_csv_auto, read_parquet, glob, read_text and every future
     *     sibling -- refused as a class rather than by name, because a list of the ones that read
     *     files is a list of the ones somebody thought of. Nothing a person needs here is lost:
     *     the datasets are already bound to names, and VALUES, subqueries and CTEs all survive;
     *   * a table NAME that is really a path. "FROM 's3://bucket/key.csv'" parses as a base table
     *     whose name is the URL, and DuckDB's replacement scan opens it -- measured on 1.1.3, and
     *     the reason this check is not only about function calls.
     *
     * The walk is generic on purpose: every object in the tree is visited and judged on the fields
     * it carries, rather than followed down the paths a FROM clause is expected to appear on. A
     * walker that navigated the structure would be a walker that has to know about every place
     * DuckDB can put a relation, and would quietly stop covering one the day a release adds it.
     */
    private static void readsOnlyWhatItWasGiven(String tree) throws AnalyticsException {
        if (tree == null) {
            throw new AnalyticsException(BY_NAME_ONLY);
        }
        JsonNode root;
        try {
            root = JSON.readTree(tree);
        } catch (IOException ex) {
            // Fail closed. A tree that cannot be read is a tree nothing has been checked in.
            throw new AnalyticsException(BY_NAME_ONLY);
        }
        walk(root, 0);
    }

    private static void walk(JsonNode node, int depth) throws AnalyticsException {
        if (depth > MAX_TREE_DEPTH) {
            throw new AnalyticsException(TOO_DEEP);
        }
        if (node.isObject()) {
            JsonNode type = node.get("type");
            if (type != null && "TABLE_FUNCTION".equals(type.asText())) {
                throw new AnalyticsException(NO_TABLE_FUNCTIONS);
            }
            // All THREE parts of a qualified name, not just the last one.
            //
            // Checking only table_name was a real, exploitable hole. DuckDB's replacement scan
            // reassembles catalog.schema.table into one location string, so a quoted SCHEMA
            // carries the URL while the table part stays innocent:
            //
            //     FROM 's3://victim/payroll.csv'        -> table_name = "s3://victim/payroll.csv"   caught
            //     FROM "s3://victim/"."payroll.csv"     -> schema_name = "s3://victim/"             MISSED
            //                                              table_name  = "payroll.csv"             passes
            //
            // Measured on 1.1.3, and the second form issued a real signed S3 GET against a bucket
            // the caller never had -- spending the session's own credential -- and, with an http
            // authority in the same position, a live outbound request to any host or port the
            // backend can reach. That is precisely the request 3.3 says this API must not be able
            // to express, re-opened one identifier over. The rule belongs to the whole name.
            for (String part : NAME_PARTS) {
                JsonNode named = node.get(part);
                if (named != null && named.isTextual()
                    && !JUST_A_NAME.matcher(named.asText()).matches()) {
                    throw new AnalyticsException(BY_NAME_ONLY);
                }
            }
        }
        for (JsonNode child : node) {
            walk(child, depth + 1);
        }
    }

    /**
     * EXPLAIN, which is the one read DuckDB will not serialise and therefore the one this class
     * has to recognise itself.
     *
     * The recognition is as small as it can be made: the keyword at the front, and a remainder
     * that has to pass the same admission as any other statement. What runs afterwards is built
     * here from a fixed prefix and that admitted remainder, so the worst a mistake in this method
     * can do is plan a read -- there is no path from it to a write.
     *
     * The parenthesised option form -- EXPLAIN (FORMAT JSON) SELECT ... -- is not admitted. It is
     * a real DuckDB syntax and this is a limit rather than a judgement about it; the user is told
     * where the parser stopped, which is the bracket.
     */
    private static Admitted explained(Connection session, String statement, int timeoutSeconds)
        throws AnalyticsException, SQLException {

        Matcher matcher = EXPLAINED.matcher(statement);
        if (!matcher.matches()) {
            throw new AnalyticsException(READS_ONLY);
        }
        String planned = matcher.group(1).trim();
        if (ANALYZED.matcher(planned).matches()) {
            throw new AnalyticsException(EXPLAIN_ONLY);
        }

        Parse parse = parse(session, planned, timeoutSeconds);
        if (parse.isMalformed()) {
            // A typo inside an EXPLAIN is still a typo, and DuckDB names the token it stopped at.
            throw parse.asTheEngineWouldHaveThrownIt();
        }
        if (!parse.isOneReadStatement()) {
            throw new AnalyticsException(READS_ONLY);
        }
        readsOnlyWhatItWasGiven(parse.getTree());
        // Not boundable: measured on 1.1.3, "SELECT * FROM (EXPLAIN SELECT 1) AS b" is a parser
        // error, so an EXPLAIN cannot be wrapped. It does not need to be -- a plan is one row
        // whatever the dataset is, so its size is a property of the query text and not of the
        // data, which is the same argument rowCount() and schemaOf() already make for themselves.
        return new Admitted("EXPLAIN " + planned, false);
    }

    /**
     * Hands the statement to DuckDB's parser as a parameter and reads back what it made of it.
     *
     * Fails closed. Every outcome that is not a clear answer -- a driver that has no
     * json_serialize_sql, a null where the verdict should be, an empty result -- is a refusal,
     * because the alternative is a gate that opens when it is broken.
     */
    private static Parse parse(Connection session, String statement, int timeoutSeconds)
        throws AnalyticsException, SQLException {

        try (PreparedStatement probe = session.prepareStatement(PARSE_PROBE)) {
            probe.setQueryTimeout(timeoutSeconds);
            probe.setString(1, statement);
            try (ResultSet parsed = probe.executeQuery()) {
                if (!parsed.next()) {
                    throw new AnalyticsException(READS_ONLY);
                }
                String failed = parsed.getString("failed");
                if (failed == null) {
                    throw new AnalyticsException(READS_ONLY);
                }
                int statements = parsed.getInt("statements");
                // Read straight after the column it belongs to. wasNull() answers for the last
                // value taken off the row, so any getString between the two would move it.
                boolean unparsed = parsed.wasNull();
                return new Parse(Boolean.parseBoolean(failed), parsed.getString("error_type"),
                    parsed.getString("message"), unparsed ? -1 : statements,
                    parsed.getString("tree"));
            }
        } catch (SQLException ex) {
            // The parser itself failing is not the user's fault and not something to guess about.
            // Reported as an engine failure so it is logged in full by explain() rather than
            // summarised into a sentence that hides which part broke.
            throw new SQLException("Analytics could not check the statement before running it: "
                + ex.getMessage(), ex);
        }
    }

    /** What DuckDB's parser said, in the four facts this gate decides on. */
    private static final class Parse {

        private final boolean failed;
        private final String errorType;
        private final String message;
        /** How many statements the text is, or -1 when the parse failed and there is no answer. */
        private final int statements;
        /** The whole serialised statement, for the questions a count cannot answer. */
        private final String tree;

        private Parse(boolean failed, String errorType, String message, int statements,
            String tree) {
            this.failed = failed;
            this.errorType = errorType;
            this.message = message;
            this.statements = statements;
            this.tree = tree;
        }

        private String getTree() {
            return this.tree;
        }

        private boolean isOneReadStatement() {
            return !this.failed && this.statements == 1;
        }

        private boolean isNothing() {
            return !this.failed && this.statements == 0;
        }

        private boolean isSeveralStatements() {
            return !this.failed && this.statements > 1;
        }

        private boolean isMalformed() {
            return this.failed && PARSE_FAILURE.equals(this.errorType);
        }

        /**
         * The parse failure, shaped like the failure the engine raises when it meets one.
         *
         * The class name is put back in front because DuckDB prints one and explain() matches on
         * it: the sentence after "Parser Error:" changes between releases, the class in front of
         * it is how DuckDB reports errors at all.
         */
        private SQLException asTheEngineWouldHaveThrownIt() {
            return new SQLException("Parser Error: " + this.message);
        }
    }

    /**
     * A statement the gate is willing to have run, and how it may be run.
     *
     * Carries the text rather than a yes: the EXPLAIN form is composed by the gate from a verified
     * remainder, so what the caller executes has to come from here rather than from the request.
     */
    public static final class Admitted {

        private final String sql;
        private final boolean boundable;

        private Admitted(String sql, boolean boundable) {
            this.sql = sql;
            this.boundable = boundable;
        }

        /** The exact text to execute. Never a rewrite of a read, only ever the user's own. */
        public String getSql() {
            return this.sql;
        }

        /** False only for EXPLAIN, which cannot be a subquery and does not need a row ceiling. */
        public boolean isBoundable() {
            return this.boundable;
        }
    }
}
