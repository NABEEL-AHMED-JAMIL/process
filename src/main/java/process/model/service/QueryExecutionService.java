package process.model.service;

import process.model.dto.QueryExecutionRequestDto;
import process.model.dto.ResponseDto;
import process.model.pojo.QuerySchedule;

/**
 * @author Nabeel Ahmed
 */
public interface QueryExecutionService {

    /** Manual "Run" -- request carries only IDs + output configuration, never a query result
     * (see QueryExecutionRequestDto's own javadoc). Synchronous: returns once the run finishes
     * (success or failure), same request/response shape as every other API in this app -- an
     * async job-queue would be the natural next step if exports start running long enough that
     * callers need to fire-and-poll instead, but nothing today needs that yet. */
    public ResponseDto execute(QueryExecutionRequestDto request) throws Exception;

    /** Same execution path as execute() above, called from ProcessCron.pollDueQuerySchedules()
     * for a single due schedule -- see this interface's own javadoc on why "one execution
     * engine, two triggers" matters. The caller (the cron poller) is responsible for seeding
     * TenantContext with the schedule's own tenantId before calling this and clearing it after,
     * the same way JwtAuthenticationFilter does for an HTTP request -- this method assumes
     * TenantContext is already correctly set, exactly like every other tenant-scoped service here. */
    public void executeForSchedule(QuerySchedule schedule) throws Exception;

    public ResponseDto fetchExecutionById(Long executionId) throws Exception;

    public ResponseDto fetchAllExecutions() throws Exception;

    public ResponseDto fetchExecutionsByQueryId(Long queryId) throws Exception;

}
