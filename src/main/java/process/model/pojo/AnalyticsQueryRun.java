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
 * One analytics query, as it actually ran.
 *
 * This is the module's first answer to "who read what, and when" -- the question phase one left
 * open when a dataset read wrote no audit row and published no event. It is a separate table from
 * {@link AnalyticsQuery} because the two rows answer to different owners: a saved query is a
 * person's own work and they may edit or throw it away, while a record of a read is evidence and
 * is not theirs to revise. The cheaper shape -- a last_run_at and a last_row_count hung off the
 * saved query -- answers neither question, because it forgets every run but the latest and
 * records nothing at all for the ad-hoc query nobody saved.
 *
 * <b>What it deliberately does not hold.</b> No credential, and no resolved bucket URL. The read
 * is described the way the rest of the module describes one, by connection alias and path, so
 * that history stays true when a connection is repointed and so that this table never becomes a
 * durable copy of where the data physically lives. The same care applies to
 * {@code errorMessage}: it holds the sentence explain() produced for the user, never the engine's
 * own string, because engine errors quote the failing statement back and that statement carries
 * the interpolated s3:// or azure:// location. A leak into a response is seen once; a leak into a
 * column is kept.
 *
 * <b>Why the fields are copied rather than read back through the saved query.</b> Everywhere else
 * in this module a copy is a liability, but a history row IS the record of what was true at the
 * time. Resolving the name, alias, path or SQL through analyticsQueryId would answer a question
 * about last Tuesday with today's values, and would answer nothing at all once the saved query is
 * deleted.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "analytics_query_run", indexes = {
    @Index(name = "idx_analytics_query_run_tenant_date", columnList = "tenant_id, date_created"),
    @Index(name = "idx_analytics_query_run_query_id", columnList = "analytics_query_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
// Plain equality, the same reading as AnalyticsDataset and AnalyticsQuery. A history row is even
// less of a shared catalogue than a saved query is: it names a path somebody read and the SQL
// they read it with, which is precisely the pair that must not cross a workspace boundary.
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class AnalyticsQueryRun implements Audited {

    /**
     * The vocabulary a run_status column may hold, and there are SEVEN of them where 05 named six.
     *
     * 05's lifecycle is QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT. All six are
     * modelled -- as {@link process.analytics.AnalyticsEngine.RunState}, which is the in-memory
     * state machine, and as the constants below, which are what a row can hold. The seventh is
     * REFUSED, and it is kept rather than folded into FAILED because it records something none of
     * the six can: a statement that never reached the engine at all, because StatementGate would
     * not admit it or the governor had no permit to give it. That is the single most interesting
     * row this table holds -- it is where an attempt to read somebody else's bucket shows up --
     * and merging it into FAILED to match a list of six would delete a security signal in order to
     * tidy an enum. The spec did not anticipate a statement gate; it does not follow that the gate
     * should stop being visible.
     *
     * Two of the six are, honestly, states this module passes through rather than states it
     * stores. QUEUED lasts at most the governor's two-second wait and RUNNING lasts the query;
     * neither is written to this table today, because a run row is written once, at the end, and
     * making it two writes is a transaction change this refactor did not take on. Both are real
     * where it counts -- RunningQueries reports them and a run can be cancelled in either -- and
     * the constants exist so the day the row is written twice, the names are already agreed.
     */

    /**
     * A query that ran and returned rows. This is 05's COMPLETED.
     *
     * Spelled SUCCESS because that is what the column already holds, what V32 documents and what
     * the history screen's pill reads. Renaming the stored value would rewrite evidence rows to
     * match a word, which is the one thing an audit table must not do for cosmetic reasons.
     */
    public static final String STATUS_SUCCESS = "SUCCESS";

    /** A query that reached the engine and failed there. */
    public static final String STATUS_FAILED = "FAILED";

    /**
     * A query the gate or the governor turned away, so it never reached the engine.
     *
     * Recorded rather than dropped: from the logs alone, a ceiling too low for the people using
     * the module looks exactly like nobody using the module -- and a refused statement is the only
     * trace that somebody asked for something they were not allowed to have.
     */
    public static final String STATUS_REFUSED = "REFUSED";

    /**
     * A query waiting for a governor permit.
     *
     * Not written by anything today. The module refuses rather than queues -- a decision recorded
     * on DuckDbAnalyticsEngine's slot ceiling -- so this state lasts two seconds and is observable
     * only in RunningQueries.
     */
    public static final String STATUS_QUEUED = "QUEUED";

    /**
     * A query holding a permit with a statement open on the engine.
     *
     * Not written by anything today, for the same reason: the row is written once, when the run is
     * already over. What can be enumerated in flight is RunningQueries, not this table.
     */
    public static final String STATUS_RUNNING = "RUNNING";

    /**
     * A query stopped because the person who started it asked for it to stop.
     *
     * Distinct from TIMED_OUT on purpose even though DuckDB reports both as
     * "INTERRUPT Error: Interrupted!": the registry knows who called cancel(), so the row says who
     * did rather than guessing from a string that cannot tell.
     */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /**
     * A query the watchdog stopped at analytics.query.timeout-seconds.
     *
     * Its own value because it used to be written as REFUSED, which made a query that ran for
     * thirty seconds indistinguishable in the history from one the governor never started. The
     * user was told the truth and the row was not.
     */
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Column(name = "created_by")
    private Long createdBy;

    // Expected to stay null. A run row is written once and never edited, and that immutability is
    // what makes it evidence; the column exists because every audited table here has it.
    @Column(name = "updated_by")
    private Long updatedBy;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "analytics_query_run_seq")
    @SequenceGenerator(name = "analytics_query_run_seq", sequenceName = "analytics_query_run_seq", allocationSize = 1)
    @Column(name = "analytics_query_run_id")
    private Long analyticsQueryRunId;

    @Column(name = "tenant_id")
    private Long tenantId;

    // No Tenant association here, unlike AnalyticsDataset and AnalyticsQuery. History is read in
    // pages of tens or hundreds, and with open-session-in-view a lazy association serialised per
    // row is a query per row. Nothing a history screen shows needs the tenant object; the id is
    // what the filter reads and all this row has ever needed.

    /**
     * The saved query this run came from, or null for an ad-hoc one.
     *
     * A plain column rather than an association, because the row it points at may be gone: the
     * changeset sets this null when a saved query is deleted, so tidying up a library removes the
     * bookmark and not the record that the data was read.
     */
    @Column(name = "analytics_query_id")
    private Long analyticsQueryId;

    @Column(name = "connection_alias", nullable = false)
    private String connectionAlias;

    @Column(name = "dataset_path", columnDefinition = "TEXT", nullable = false)
    private String datasetPath;

    // As the caller submitted it, not as the engine rewrote it. The LIMIT the governor adds is
    // reconstructable from the configuration; what the person actually asked for is not
    // reconstructable from anything, and it is what makes a run re-runnable from history.
    @Column(name = "query_text", columnDefinition = "TEXT", nullable = false)
    private String queryText;

    // One of the STATUS_ constants above. A String rather than an enum because these names are
    // shared with a screen and a JSON payload, and adding one should not be a schema change --
    // which is what let CANCELLED and TIMED_OUT arrive without touching V32. The column is
    // VARCHAR(24) and the longest name is nine characters, so there is room for the rest of the
    // vocabulary too.
    @Column(name = "run_status", length = 24, nullable = false)
    private String runStatus;

    // Null when there was no result at all. A failure and a refusal both read no rows, and zero
    // is a real answer that neither of them gave.
    @Column(name = "row_count")
    private Long rowCount;

    // Wall clock inside the engine. Null on a refusal, which never reached it.
    @Column(name = "duration_ms")
    private Long durationMs;

    // The sentence the user was shown. See the class comment: never the raw engine string.
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated = new Timestamp(System.currentTimeMillis());

    public AnalyticsQueryRun() {}

    public Long getAnalyticsQueryRunId() { return analyticsQueryRunId; }
    public void setAnalyticsQueryRunId(Long analyticsQueryRunId) { this.analyticsQueryRunId = analyticsQueryRunId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getAnalyticsQueryId() { return analyticsQueryId; }
    public void setAnalyticsQueryId(Long analyticsQueryId) { this.analyticsQueryId = analyticsQueryId; }

    public String getConnectionAlias() { return connectionAlias; }
    public void setConnectionAlias(String connectionAlias) { this.connectionAlias = connectionAlias; }

    public String getDatasetPath() { return datasetPath; }
    public void setDatasetPath(String datasetPath) { this.datasetPath = datasetPath; }

    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }

    public String getRunStatus() { return runStatus; }
    public void setRunStatus(String runStatus) { this.runStatus = runStatus; }

    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

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
