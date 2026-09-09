package process.model.service;

import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsQuery;
import process.model.pojo.AnalyticsQueryRun;

/**
 * The library: saved analytics queries, and the record of what has been run.
 *
 * Kept apart from AnalyticsQueryService, which owns execution. Nothing here opens a session,
 * builds SQL or touches the engine -- this is a table of names and a table of receipts, and the
 * rule that a query endpoint must not open a second door beside the locked one stays true of a
 * service that never opens one at all.
 *
 * <b>recordRun is not an endpoint, and must not become one.</b> History is written by the code
 * that ran the query, from what it observed. If a client could post "I ran this", the record
 * would be forgeable, and a forgeable audit trail is worse than no audit trail: it invites people
 * to rely on it.
 *
 * @author Nabeel Ahmed
 */
public interface AnalyticsQueryLibraryService {

    /** Every saved query the caller's workspace can see, newest first. */
    ResponseDto fetchAllQueries() throws Exception;

    /** One saved query, or a not-found for anything the caller does not own. */
    ResponseDto fetchQueryById(Long analyticsQueryId) throws Exception;

    /**
     * Creates a saved query, or updates one the caller owns when the payload carries an id.
     *
     * The stored row is built here rather than bound from the request. tenantId and the audit
     * columns come from the signed-in context, so "save this into another workspace" is not a
     * request this method can be made to honour.
     */
    ResponseDto saveQuery(AnalyticsQuery payload) throws Exception;

    /** Renames a saved query. Separate from saveQuery because a library renames in place. */
    ResponseDto renameQuery(Long analyticsQueryId, String queryName) throws Exception;

    /**
     * Deletes a saved query outright.
     *
     * A hard delete, matching analytics_dataset, which carries no status column either: nothing
     * resolves through a saved query and no report counts one, so a tombstone would earn nothing
     * and would keep a name in a list somebody meant to be rid of. The history of what it ran
     * survives independently -- a run row keeps its own copy of the SQL and the location, and the
     * changeset only nulls the link back.
     */
    ResponseDto deleteQuery(Long analyticsQueryId) throws Exception;

    /**
     * Recent runs, newest first: all of the caller's workspace, or one saved query's own.
     *
     * The window is clamped rather than taken as given. The table is deliberately never pruned,
     * so it is the reads that have to stay bounded.
     */
    ResponseDto fetchRecentRuns(Long analyticsQueryId, Integer limit) throws Exception;

    /**
     * Writes the record that a query ran. Called by the code that ran it, never by a client.
     *
     * The caller fills in what it observed -- the location, the SQL as submitted, the outcome,
     * the row count and the duration -- and this method owns the rest: the tenant, the author,
     * the timestamp, and the guarantee that nothing naming a bucket or a host reaches the column
     * where it would be kept.
     *
     * Returns the persisted row, or null when there was no signed-in context to attribute it to;
     * recording is never allowed to be the reason a query fails, so a caller should not treat a
     * null as an error.
     */
    AnalyticsQueryRun recordRun(AnalyticsQueryRun run);

}
