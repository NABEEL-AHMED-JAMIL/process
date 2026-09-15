package process.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every bound Analytics Studio refuses to cross, in one place.
 *
 * These are not tuning knobs bolted on afterwards. The feature's whole purpose is to let a user
 * point a query engine at an arbitrary file, so the interesting question was never "can DuckDB
 * read this" but "what stops one careless request taking the backend down with it". DuckDB runs
 * INSIDE this JVM: a scan with no ceiling competes for memory with the ETL dispatcher in the
 * same container, and a result set with no cap gets serialised to a browser that then has to
 * hold it.
 *
 * Collected as one bean rather than scattered @Value fields so the policy can be read in one
 * sitting, asserted in one test, and changed per environment without hunting.
 *
 * Three of the fields below are switches rather than ceilings, and they are here for the same
 * reason the ceilings are: an operator asking "what will this feature do in this environment"
 * should get the whole answer from one class. A switch also needs a SENTENCE, not just a boolean
 * -- a feature that is off should say so in words a user can act on -- so each one carries the
 * refusal it produces, and requireEnabled/requireBenchmarkEnabled/requireParquetConversionEnabled
 * are what a caller invokes rather than reading the flag and inventing its own wording.
 *
 * @author Nabeel Ahmed
 */
@Component
public class AnalyticsLimits {

    /**
     * Whether Analytics Studio runs at all in this environment.
     *
     * The rollout switch, and the only one of these that answers a question about the feature
     * rather than about a query. It exists so the module can reach internal users, then a test
     * tenant, then production, without a branch per environment: every deployment carries the
     * same jar and the environment decides. The role floor on the controllers is a different
     * question and stays where it is -- it says WHO may call analytics, never WHETHER analytics
     * is on.
     *
     * Off means a designed refusal, never a 404: a missing route tells a user their client is
     * broken, and this is a deliberate operational state somebody chose. See requireEnabled.
     */
    @Value("${analytics.enabled:true}")
    private boolean enabled = true;

    /**
     * How long a single query may run before it is cancelled.
     *
     * Applies to the whole statement, not to a row. Chosen to be longer than an honest scan of a
     * large Parquet file and far shorter than a request thread should ever be held.
     *
     * 120 seconds is spec 14's number (it states 120000 ms; the unit here stays seconds because
     * that is what JDBC's setQueryTimeout and this module's user-facing timeout sentence both
     * speak, and a second name for the same limit in a second unit is how the two drift apart).
     * The earlier 30 was chosen before the watchdog existed, when the driver ignored
     * setQueryTimeout and nothing actually stopped a query: a short number was the only bound
     * there was. AnalyticsQueryService.stopAfterTimeout now cancels for real, and the cost of a
     * long query is bounded by the permit it holds rather than by the thread it occupies -- so
     * the honest ceiling is "longer than a large honest scan", which 30 seconds was not.
     */
    @Value("${analytics.query.timeout-seconds:120}")
    private int timeoutSeconds;

    /**
     * The most rows any single query may return to the caller.
     *
     * Enforced by AnalyticsQueryService.bounded, which wraps the query in a LIMIT rather than
     * reading everything and truncating afterwards, so the rows are never materialised in the
     * first place. It also clamps the preview page size, which is the same ceiling reached
     * through a different door.
     *
     * A ceiling on rows RETURNED, not on rows read. A query that scans a billion rows to return
     * one is bounded by timeout-seconds and by the DuckDB memory limit below, not by this.
     *
     * <b>100,000 is spec 14's number and it is the one limit here whose cost is not paid by
     * DuckDB.</b> Everything past the engine holds the whole result in this JVM at once: query()
     * builds List&lt;List&lt;String&gt;&gt;, Jackson serialises it, and AnalyticsExportService.download then
     * makes a String of it, a byte[] of that and a base64 String of that -- four live copies of
     * one export. Ten times the rows is ten times all of it, times max-concurrent-queries, in the
     * container the ETL dispatcher shares. The ceiling is therefore raised to what the spec asks
     * for AND declared per environment above all the others, because this is the number an
     * operator is most likely to have to lower. The paired changes that would make it comfortable
     * -- a streamed export instead of base64-in-JSON, and a preview page size that does not
     * inherit this ceiling -- belong to the export and query services and are recorded as open.
     */
    @Value("${analytics.query.max-rows:100000}")
    private int maxRows;

    /** Rows in one page of the dataset preview. A page is what the browser gets; never a file. */
    @Value("${analytics.preview.page-size:100}")
    private int previewPageSize;

    /**
     * The most CELLS a single response may carry. Zero or below removes the bound.
     *
     * <b>Cells, not rows, because rows are the wrong unit.</b> The cost of a response is rows
     * times columns: 100,000 rows of ten columns was measured at 12.9 MB of JSON and 61 MB of
     * heap. The same row ceiling over a forty-column file costs four times that, so no row number
     * bounds a payload -- and `max-rows` was being asked to do a job it cannot do.
     *
     * 100,000 cells is about 1.35 MB of JSON and 6.4 MB of heap, and four of them -- the governor's
     * ceiling -- is about 26 MB rather than the 244 MB the row limit alone allowed. It is also the
     * unit the BROWSER pays in: one cell is one table cell.
     *
     * This does not replace max-rows. That still bounds the QUERY, as a LIMIT the engine applies;
     * this bounds what comes back over the wire, and a result stopped by either says `truncated`.
     */
    @Value("${analytics.query.max-response-cells:100000}")
    private int maxResponseCells = 100000;

    /**
     * The largest page the preview endpoint will hand out, whatever the caller asks for.
     *
     * <b>This closes a second door.</b> The preview's page size was clamped only to max-rows, so
     * `?pageSize=100000` returned a hundred-thousand-row payload through an endpoint meant to
     * return a page -- the same cost as the query path, past a limit that was only ever guarding
     * the query path. A preview is a page somebody is looking at; a thousand rows is already more
     * than anyone reads.
     */
    @Value("${analytics.preview.max-page-size:1000}")
    private int maxPreviewPageSize = 1000;

    /**
     * How many analytics queries may run at once across the whole application.
     *
     * A hard ceiling, because each one can hold DuckDB's memory limit at the same time. Requests
     * past it are refused immediately with a clear message rather than queued: a caller who
     * waits behind five one-gigabyte scans has already lost, and telling them so is kinder than
     * a request that eventually times out.
     */
    @Value("${analytics.query.max-concurrent:4}")
    private int maxConcurrentQueries;

    /*
     * There is no analytics.profile.sample-rows, and this comment is what is left of it. A plain
     * comment rather than javadoc on purpose: there is no field for it to document.
     *
     * Document 14 asks for the profile's INPUT to be bounded. The number lived here, declared and
     * enforced nowhere, with the gap written down beside it. Enforcing it was measured before it
     * was decided, and the measurement says the property AS SPECIFIED cannot pay for itself. Two
     * million rows x six columns of CSV (77 MB), duckdb_jdbc 1.1.3, threads=2, warm:
     *
     *   SUMMARIZE over the whole relation      396 ms
     *   USING SAMPLE 200000 ROWS               374 ms   <- what "sample-rows" means
     *   USING SAMPLE 10%                       203 ms
     *   LIMIT 200000                           118 ms
     *   SELECT count(*), for scale             115 ms
     *
     * A row-count sample is a RESERVOIR sample: it reads every row and keeps some. It saves 5% and
     * buys a number that is no longer the truth. The 118 ms line is a first-N sample, fast because
     * it stops reading -- and biased by whatever the file is ordered by, which for an exported
     * dataset is usually a date. Neither is a profile worth showing, and the scan is only 115 ms of
     * the 396: the cost is SUMMARIZE's aggregation, not the read the property would shorten.
     *
     * So the knob is gone rather than left switchable-and-inert. An operator who sets a property
     * that does nothing has been told something false -- the same defect that made
     * analytics.benchmark.enabled and analytics.parquet.conversion-enabled worth wiring up in this
     * pass, and this one could not be wired up honestly. If it is ever wanted, the form that would
     * work is the percentage sample, with DatasetProfileDto carrying the fact that it IS a sample
     * and the Quality tab saying so. That is a feature, not a limit.
     */

    /**
     * Whether the benchmark harness may be invoked.
     *
     * Separate from analytics.enabled because it is the opposite kind of switch: analytics is a
     * feature users need, the benchmark is a load generator that deliberately runs the same read
     * five times and holds a governor permit for minutes. An operator who wants analytics up
     * during an incident and load generation off has to be able to say that, and PLATFORM_ADMIN
     * is not the answer -- it says who may generate the load, not whether this environment will
     * accept it at all.
     */
    @Value("${analytics.benchmark.enabled:true}")
    private boolean benchmarkEnabled = true;

    /**
     * Whether a result may be written back as Parquet.
     *
     * The narrowest of the three switches, and the one with a real asymmetry behind it: Parquet
     * is written to a bucket by the engine and cannot be offered as a download at all
     * (AnalyticsExportService refuses that, because the engine must not be given a local
     * destination). So this governs write-back only, and switching it off leaves CSV and JSON
     * write-back working rather than turning export off.
     */
    @Value("${analytics.parquet.conversion-enabled:true}")
    private boolean parquetConversionEnabled = true;

    /**
     * DuckDB's own memory ceiling per connection, as a DuckDB size string.
     *
     * When a query would exceed it DuckDB spills or fails; either is better than the container's
     * OOM killer taking the whole backend, which is what happens with no limit set.
     */
    @Value("${analytics.duckdb.memory-limit:512MB}")
    private String memoryLimit;

    /** Worker threads per DuckDB connection. Bounded so analytics cannot starve the dispatcher. */
    @Value("${analytics.duckdb.threads:2}")
    private int threads;

    /**
     * How many days of run history to keep. <b>Zero, the default, keeps everything.</b>
     *
     * OFF by default, and that is deliberate rather than timid. V32__analytics_query.sql argued
     * at length for leaving analytics_query_run unpruned: the question it answers -- "who read
     * that bucket, and when" -- is asked weeks later by somebody who was not there, losing rows
     * is the one thing that cannot be undone afterwards, and adding a retention rule later can be
     * done over data that is still there. That reasoning still holds.
     *
     * What changed is the RATE. History is now written for every dataset read and not only for
     * statements somebody typed, so a reader paging a 1,500-page dataset leaves 1,500 rows. That
     * is a large multiple, but it is still a person clicking something, behind a governor that
     * admits four concurrent queries -- not the "machine issuing queries on a schedule" that the
     * changeset named as the condition for changing its mind.
     *
     * So the mechanism exists and the policy does not. An operator who has decided what their
     * audit retention is sets this; nobody has that decision made for them by a default.
     */
    @Value("${analytics.history.retention-days:0}")
    private int historyRetentionDays;

    /**
     * The shape DuckDB accepts for a size setting, and the shape this class refuses to hold.
     *
     * The same rule DuckDbSessionFactory applies before interpolating the value into SET
     * memory_limit. It is checked here as well, and not only there, because there it is found out
     * one query at a time by a user: a mistyped ceiling starts an application that looks healthy
     * and fails every analytics request. Checking the string where the policy lives is what lets
     * the health indicator report it at deploy time instead.
     */
    private static final String SIZE_SHAPE = "[0-9]+[A-Za-z]{0,3}";

    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * The refusal a caller returns when analytics is switched off.
     *
     * Thrown rather than returned so it lands in the AnalyticsException catch every analytics
     * endpoint already has, which turns it into an HTTP 200 with status ERROR and this sentence
     * -- the platform's shape for "your request was understood and the answer is no". A
     * @ConditionalOnProperty on the controllers would have been fewer lines and would produce a
     * 404, which tells a user their client is broken rather than that an operator turned
     * something off.
     */
    public void requireEnabled() throws AnalyticsException {
        if (!this.enabled) {
            throw new AnalyticsException("Analytics Studio is switched off in this environment. "
                + "Nothing is wrong with your dataset or your query -- an administrator has to "
                + "turn it back on.");
        }
    }

    /**
     * How many times a built-in read is ATTEMPTED, not retried. One means no retry.
     *
     * Two: the original and one more. A transient failure of the object store is usually gone
     * within a second, and a second attempt catches nearly all of what a third would -- while a
     * third costs a third full scan of the same file against a governor that admits four queries
     * at a time across the whole JVM. Retries here are paid for by every other reader, so the
     * ceiling is deliberately mean.
     */
    @Value("${analytics.storage.retry-attempts:2}")
    private int storageRetryAttempts = 2;

    /**
     * How long to wait before the second attempt, in milliseconds.
     *
     * A flat wait, not exponential backoff. There is only ever one retry, so "exponential" would
     * describe a single number, and 250ms is long enough for a reset connection to be re-dialled
     * without being long enough for anyone to notice the read was slow.
     */
    @Value("${analytics.storage.retry-backoff-ms:250}")
    private long storageRetryBackoffMs = 250L;

    public int getMaxResponseCells() {
        return this.maxResponseCells;
    }

    public int getMaxPreviewPageSize() {
        return this.maxPreviewPageSize;
    }

    public int getStorageRetryAttempts() {
        return this.storageRetryAttempts;
    }

    public long getStorageRetryBackoffMs() {
        return this.storageRetryBackoffMs;
    }

    public int getHistoryRetentionDays() {
        return this.historyRetentionDays;
    }

    public int getTimeoutSeconds() {
        return this.timeoutSeconds;
    }

    public int getMaxRows() {
        return this.maxRows;
    }

    public int getPreviewPageSize() {
        return this.previewPageSize;
    }

    public int getMaxConcurrentQueries() {
        return this.maxConcurrentQueries;
    }

    public boolean isBenchmarkEnabled() {
        return this.benchmarkEnabled;
    }

    /** The benchmark's refusal, worded so a platform admin knows it is the environment saying no. */
    public void requireBenchmarkEnabled() throws AnalyticsException {
        if (!this.benchmarkEnabled) {
            throw new AnalyticsException("The analytics benchmark is switched off in this "
                + "environment. It generates real load on purpose, so it can be turned off "
                + "without turning Analytics Studio off.");
        }
    }

    public boolean isParquetConversionEnabled() {
        return this.parquetConversionEnabled;
    }

    /** Write-back's Parquet refusal. Names the formats that still work, because two of them do. */
    public void requireParquetConversionEnabled() throws AnalyticsException {
        if (!this.parquetConversionEnabled) {
            throw new AnalyticsException("Writing Parquet is switched off in this environment. "
                + "Write the result as CSV or JSON, or ask an administrator to turn Parquet "
                + "conversion back on.");
        }
    }

    public String getMemoryLimit() {
        return this.memoryLimit;
    }

    public int getThreads() {
        return this.threads;
    }

    /**
     * Every way this configuration is wrong, in sentences, or an empty list.
     *
     * Exists because these mistakes are all silent. An application whose memory-limit is
     * "512 MB" with a space starts perfectly and then throws on the first line of every session;
     * one whose max-concurrent is 0 gets a semaphore of 1 because AnalyticsQueryService clamps it
     * and nobody finds out the number was ignored. Neither is discoverable from outside: this
     * deployment does not expose /actuator/env, deliberately, so a health detail is the only
     * place an operator can be told.
     *
     * Returns problems rather than throwing them. A misconfigured analytics module must not stop
     * an application whose other half is the ETL dispatcher -- see AnalyticsHealthIndicator for
     * the same argument applied to the health status itself.
     */
    public List<String> configurationProblems() {
        List<String> problems = new ArrayList<>();
        if (this.memoryLimit == null || !this.memoryLimit.matches(SIZE_SHAPE)) {
            problems.add("analytics.duckdb.memory-limit must look like 512MB or 2GB, so every "
                + "session will fail to open until it does");
        }
        if (this.threads < 1) {
            problems.add("analytics.duckdb.threads is " + this.threads
                + ", and a session needs at least one worker thread");
        }
        if (this.maxConcurrentQueries < 1) {
            problems.add("analytics.query.max-concurrent is " + this.maxConcurrentQueries
                + ", which is silently treated as 1 rather than as no analytics at all");
        }
        if (this.maxRows < 1) {
            problems.add("analytics.query.max-rows is " + this.maxRows
                + ", so every query would be bounded to nothing");
        }
        if (this.previewPageSize < 1) {
            problems.add("analytics.preview.page-size is " + this.previewPageSize
                + ", so a preview would ask for a page of no rows");
        }
        if (this.timeoutSeconds < 1) {
            // Not a typo-check: AnalyticsQueryService reads a non-positive timeout as JDBC does,
            // meaning no limit at all, so this is the one wrong value that removes a bound
            // instead of tightening one.
            problems.add("analytics.query.timeout-seconds is " + this.timeoutSeconds
                + ", which means no timeout at all rather than a short one");
        }
        return Collections.unmodifiableList(problems);
    }
}
