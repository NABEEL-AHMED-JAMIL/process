package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * One measured read of one dataset, kept so that a claim about speed can be checked instead of
 * remembered.
 *
 * <b>Why a row here is mostly context and only four numbers.</b> The module has been advising
 * users to try "Parquet instead of CSV" since phase one, and nobody had measured whether that is
 * true on this deployment. The measurement is the easy half. The hard half is that a stored
 * duration is read months later by somebody who has forgotten everything that was obvious on the
 * day, and "CSV: 412 ms" answers no question they will actually have: 412 ms of what, over how
 * many rows, after how many discarded warmups, and how far did the five runs sit apart? Every
 * field below except the four aggregates exists to stop the aggregate being misread.
 *
 * <b>The confound this row is shaped around.</b> A file open in the Studio costs THREE governed
 * sessions -- the schema read, the row count and the first page -- so timing "opening a dataset"
 * includes the per-session cost three times, while timing one query includes it once and is not
 * what a user experiences. Both are legitimate things to measure and they are not comparable with
 * each other, so the choice is recorded three ways: {@code measureKind} for a machine,
 * {@code measuredWhat} for a person, and {@code sessionsPerRun} as the number that makes the
 * difference arithmetic rather than a matter of interpretation.
 *
 * <b>What this row does not contain.</b> No bucket, no endpoint, no region, no credential -- the
 * location is named by connection alias and path, exactly as {@link AnalyticsDataset} and
 * {@link AnalyticsQuery} name it, so a connection later repointed elsewhere does not leave this
 * table asserting where the bytes were. And no evidence at all about reading in place versus
 * loading into Postgres: the application has no load-into-Postgres path to time against, so a row
 * here is about formats and about session cost, and must not be quoted for the other claim.
 *
 * @author Nabeel Ahmed
 */
@Entity
// analytics_benchmark_result rather than benchmark_result, though the class is BenchmarkResult:
// the class name is the one the module's plan has used throughout, and the table joins the
// analytics_ family so that somebody reading the schema alone can see which feature owns it. A
// bare "benchmark_result" in a database that also holds job_queue and source_task would read as
// the platform's benchmarks rather than this module's.
@Table(name = "analytics_benchmark_result", indexes = {
    @Index(name = "idx_analytics_benchmark_result_tenant_date", columnList = "tenant_id, date_created"),
    @Index(name = "idx_analytics_benchmark_result_batch", columnList = "batch_id"),
    @Index(name = "idx_analytics_benchmark_result_label", columnList = "benchmark_label")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
// Plain equality, the same reading AnalyticsDataset and AnalyticsQuery take, and deliberately not
// StorageConnection's "(tenant_id = :tenantId or tenant_id is null)". That form admits the
// platform's own rows to every tenant, which is right for a catalogue the whole application
// resolves buckets through and wrong here: a benchmark row names a connection alias and a path
// inside it, which is to say where one workspace keeps its data.
//
// As things stand every row will carry a null tenant, because only a platform admin can run a
// benchmark. The filter is still the one that matters: it makes those rows visible to platform
// admins alone rather than published to everybody, and if the floor is ever lowered so a tenant
// admin can measure their own datasets, their rows are scoped from the first one written.
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class BenchmarkResult implements Audited {

    /**
     * A file open as the Studio performs one: the schema read, the row count and the first page.
     *
     * Three governed sessions, and that is the whole reason this constant is public and named
     * rather than a 3 written into the service. It is what a user pays when they click a file, and
     * it is the number that makes a FILE_OPEN row incomparable with a QUERY row.
     */
    public static final String MEASURE_FILE_OPEN = "FILE_OPEN";

    /** One statement somebody wrote, through the governor and the lock-down. One session. */
    public static final String MEASURE_QUERY = "QUERY";

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Column(name = "created_by")
    private Long createdBy;

    // Expected to stay null. A measurement is written once; a number that can be edited after the
    // fact is not evidence. The column exists because every audited table here has it.
    @Column(name = "updated_by")
    private Long updatedBy;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "analytics_benchmark_result_seq")
    @SequenceGenerator(name = "analytics_benchmark_result_seq",
        sequenceName = "analytics_benchmark_result_seq", allocationSize = 1)
    @Column(name = "analytics_benchmark_result_id")
    private Long analyticsBenchmarkResultId;

    // Null for a platform admin, who has no tenant of their own. See the filter comment above for
    // why that is a row only platform admins can see rather than one shared with everybody.
    @Column(name = "tenant_id")
    private Long tenantId;

    // No Tenant association, the same call AnalyticsQueryRun made: nothing a results screen shows
    // needs the tenant object, and with open-session-in-view a lazy association serialised per row
    // is a query per row.

    /**
     * One invocation of the harness.
     *
     * The rows that are meant to be compared with each other share this. A comparison is only
     * sound between numbers taken minutes apart, on the same JVM, against the same object store --
     * and a label on its own would happily invite comparing September's CSV row with November's
     * Parquet one, which is a measurement of the intervening two months.
     */
    @Column(name = "batch_id", length = 64, nullable = false)
    private String batchId;

    /**
     * The operator's name for what is being compared -- "orders-1m", "orders-50m".
     *
     * Required rather than optional, because this is how more than one SIZE gets recorded. The
     * harness measures the datasets it is handed; the label is what says which rung of the ladder
     * they were, and an unlabelled measurement is a number nobody can place afterwards.
     */
    @Column(name = "benchmark_label", nullable = false)
    private String benchmarkLabel;

    /** {@link #MEASURE_FILE_OPEN} or {@link #MEASURE_QUERY}. Half the answer to "412 ms of what?". */
    @Column(name = "measure_kind", length = 24, nullable = false)
    private String measureKind;

    /**
     * The other half, in words, and stored rather than derived on purpose.
     *
     * The enum-ish name above can be renamed, extended or reinterpreted by a later phase. The
     * sentence recorded on the day says what this particular number timed and stays true whatever
     * happens to the vocabulary around it, so a reader of one row never has to go and find the
     * code that wrote it.
     */
    @Column(name = "measured_what", columnDefinition = "TEXT", nullable = false)
    private String measuredWhat;

    /**
     * How many governed sessions one measured run cost: three for a file open, one for a query.
     *
     * Nothing derives anything from this. It exists so that a reader comparing a three-session
     * number with a one-session number can see that is what they are doing.
     */
    @Column(name = "sessions_per_run", nullable = false)
    private Integer sessionsPerRun;

    @Column(name = "connection_alias", nullable = false)
    private String connectionAlias;

    @Column(name = "dataset_path", columnDefinition = "TEXT", nullable = false)
    private String datasetPath;

    /**
     * CSV, TSV, JSON or PARQUET, as the resolver derived it from the path at measurement time.
     *
     * The whole comparison turns on this column, so it is taken from DatasetResolver's answer and
     * never from anything the caller said about the file.
     */
    @Column(name = "dataset_format", length = 24, nullable = false)
    private String datasetFormat;

    /**
     * The statement measured, as submitted -- before bounded() wrapped it. Null for a file open.
     *
     * The harness has no default SQL and requires this for a QUERY measurement, which looks
     * unhelpful until the obvious default is written down: {@code SELECT count(*) FROM dataset} is
     * answered from Parquet's footer without reading a row and from every byte of a CSV. A default
     * like that would manufacture a spectacular Parquet win that says nothing about reading data,
     * silently, in the one tool built to check that claim.
     */
    @Column(name = "query_text", columnDefinition = "TEXT")
    private String queryText;

    // Measured by the harness outside the timed sample, not taken from the request: a benchmark
    // whose most explanatory columns were hearsay would be worth less than no benchmark.
    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "column_count")
    private Integer columnCount;

    /**
     * Size on the object store, or null when it could not be established honestly.
     *
     * Null for a multi-file dataset -- a glob names no single object to ask about -- and null when
     * the store would not answer. Null means "not known", never zero.
     *
     * It matters more than it looks. Parquet's advantage over CSV is largely compression, so a
     * duration with no size beside it cannot tell a reader whether the format won or whether one
     * file simply had less of the data in it.
     */
    @Column(name = "dataset_bytes")
    private Long datasetBytes;

    /**
     * How many runs were discarded before the timer was believed.
     *
     * They are discarded because the first execution of this path in a JVM measures things that
     * have nothing to do with the dataset: HotSpot still interpreting the JDBC and result-marshal
     * code, DuckDB's httpfs extension loading into the process, and the object-store client
     * opening its first connection. Recorded rather than assumed, and allowed to be zero, because
     * a row that discarded the cold run and a row that did not are different measurements.
     */
    @Column(name = "warmup_runs", nullable = false)
    private Integer warmupRuns;

    @Column(name = "measured_runs", nullable = false)
    private Integer measuredRuns;

    // The spread, not just the middle. A single number from a single run of a JIT-compiled JVM
    // against a network object store is noise wearing the costume of a measurement; min and max
    // are what let a reader see whether these five runs agreed with each other.
    @Column(name = "min_ms", nullable = false)
    private Long minMs;

    /**
     * The headline figure, and the median rather than the mean deliberately.
     *
     * One slow run drags a mean of five a long way, and the runs that go slow here go slow for
     * reasons that are not about the file.
     */
    @Column(name = "median_ms", nullable = false)
    private Long medianMs;

    @Column(name = "max_ms", nullable = false)
    private Long maxMs;

    @Column(name = "mean_ms", nullable = false)
    private Long meanMs;

    /**
     * Every kept run, comma separated, in the order they ran.
     *
     * The four aggregates above are all recomputable from this, which is the point: a reader who
     * distrusts the summary -- and they should, summaries are where measurements turn into
     * slogans -- can look at the samples. Kept in run order rather than sorted so that a sequence
     * which got steadily faster is visible as one.
     */
    @Column(name = "run_durations_ms", columnDefinition = "TEXT", nullable = false)
    private String runDurationsMs;

    /**
     * The analytics limits in force when this ran, as one compact string.
     *
     * A QUERY measurement stops at the row ceiling, so a row measured under max-rows=1000 is not
     * comparable with one measured under max-rows=100000 -- and once somebody edits a properties
     * file, the ceiling that applied is not recoverable from anything else. One string rather than
     * four columns because nothing queries on it: it is read by a person deciding whether two rows
     * may be compared at all.
     */
    @Column(name = "limits_at_run", nullable = false)
    private String limitsAtRun;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated = new Timestamp(System.currentTimeMillis());

    public BenchmarkResult() {}

    public Long getAnalyticsBenchmarkResultId() { return analyticsBenchmarkResultId; }
    public void setAnalyticsBenchmarkResultId(Long analyticsBenchmarkResultId) { this.analyticsBenchmarkResultId = analyticsBenchmarkResultId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getBenchmarkLabel() { return benchmarkLabel; }
    public void setBenchmarkLabel(String benchmarkLabel) { this.benchmarkLabel = benchmarkLabel; }

    public String getMeasureKind() { return measureKind; }
    public void setMeasureKind(String measureKind) { this.measureKind = measureKind; }

    public String getMeasuredWhat() { return measuredWhat; }
    public void setMeasuredWhat(String measuredWhat) { this.measuredWhat = measuredWhat; }

    public Integer getSessionsPerRun() { return sessionsPerRun; }
    public void setSessionsPerRun(Integer sessionsPerRun) { this.sessionsPerRun = sessionsPerRun; }

    public String getConnectionAlias() { return connectionAlias; }
    public void setConnectionAlias(String connectionAlias) { this.connectionAlias = connectionAlias; }

    public String getDatasetPath() { return datasetPath; }
    public void setDatasetPath(String datasetPath) { this.datasetPath = datasetPath; }

    public String getDatasetFormat() { return datasetFormat; }
    public void setDatasetFormat(String datasetFormat) { this.datasetFormat = datasetFormat; }

    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }

    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }

    public Integer getColumnCount() { return columnCount; }
    public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }

    public Long getDatasetBytes() { return datasetBytes; }
    public void setDatasetBytes(Long datasetBytes) { this.datasetBytes = datasetBytes; }

    public Integer getWarmupRuns() { return warmupRuns; }
    public void setWarmupRuns(Integer warmupRuns) { this.warmupRuns = warmupRuns; }

    public Integer getMeasuredRuns() { return measuredRuns; }
    public void setMeasuredRuns(Integer measuredRuns) { this.measuredRuns = measuredRuns; }

    public Long getMinMs() { return minMs; }
    public void setMinMs(Long minMs) { this.minMs = minMs; }

    public Long getMedianMs() { return medianMs; }
    public void setMedianMs(Long medianMs) { this.medianMs = medianMs; }

    public Long getMaxMs() { return maxMs; }
    public void setMaxMs(Long maxMs) { this.maxMs = maxMs; }

    public Long getMeanMs() { return meanMs; }
    public void setMeanMs(Long meanMs) { this.meanMs = meanMs; }

    public String getRunDurationsMs() { return runDurationsMs; }
    public void setRunDurationsMs(String runDurationsMs) { this.runDurationsMs = runDurationsMs; }

    public String getLimitsAtRun() { return limitsAtRun; }
    public void setLimitsAtRun(String limitsAtRun) { this.limitsAtRun = limitsAtRun; }

    public Timestamp getDateCreated() { return dateCreated; }
    public void setDateCreated(Timestamp dateCreated) { this.dateCreated = dateCreated; }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

    @Override
    public Long getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public Long getUpdatedBy() {
        return updatedBy;
    }

    @Override
    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    @Override
    public String getCreatedByName() {
        return createdByName;
    }

    @Override
    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    @Override
    public String getUpdatedByName() {
        return updatedByName;
    }

    @Override
    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }
}
