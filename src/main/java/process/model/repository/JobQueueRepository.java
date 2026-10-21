package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.JobQueue;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface JobQueueRepository extends CrudRepository<JobQueue, Long> {

    // Ordered by id: the fetch is capped, so without this which rows a busy queue hands over is
    // whatever the planner returns, and a job can sit behind newer ones indefinitely.
    // next_attempt_at is how a backoff is enforced: a run awaiting retry sits in Queue like any
    // other, and is simply not eligible until the moment written on it. The cutoff arrives as a
    // parameter rather than being read here as now(). It used to be because the database's now() was
    // UTC while the columns held Chicago wall-clock; since V100 both are instants and that reason is
    // gone (MIG-163), but a paged pass still needs one cutoff for every page, so it stays a parameter.
    //
    // Every time parameter in this repository is a Timestamp -- an instant -- made from the application's
    // Chicago wall-clock by TransactionServiceImpl (BusinessTime.timestampOf). A LocalDateTime here would be
    // bound through the JVM's zone.
    //
    // prepared_at: the dispatcher takes only runs the pre-dispatch phase has finished with (MIG-134),
    // so no model call and no configuration check is left to happen inside the dispatch lock.
    @Query(value = "select job_queue.* from job_queue where UPPER(job_status) = 'QUEUE' and job_send = false "
        + "and prepared_at is not null "
        + "and (next_attempt_at is null or next_attempt_at <= ?2) "
        + "order by job_queue_id asc limit ?1 ", nativeQuery = true)
    List<JobQueue> findAllJobForTodayWithLimit(Long limit, Timestamp eligibleAt);

    /**
     * Runs waiting for the pre-dispatch phase, for the caller's transaction (MIG-134): queued, not sent,
     * not yet prepared, due, and not leased to a preparer -- locked SKIP LOCKED so two replicas take
     * different runs. The caller leases what it takes in the same transaction.
     */
    @Query(value = "select job_queue_id from job_queue where UPPER(job_status) = 'QUEUE' and job_send = false "
        + "and prepared_at is null "
        + "and (next_attempt_at is null or next_attempt_at <= :now) "
        + "and (prepare_lease_until is null or prepare_lease_until < :now) "
        + "order by job_queue_id asc limit :limit for update skip locked", nativeQuery = true)
    List<Long> findRunsToPrepare(@Param("now") Timestamp now, @Param("limit") int limit);

    @Transactional
    @Modifying
    @Query(value = "update job_queue set prepare_lease_until = :until where job_queue_id in (:ids)", nativeQuery = true)
    int leaseForPreparation(@Param("ids") List<Long> ids, @Param("until") Timestamp until);

    /**
     * Hands a prepared run to the dispatcher. Only while it is still queued and unsent: a run closed or
     * dispatched meanwhile is left as it is. The correlation id is stamped here if the run has none yet,
     * so the preparation logs under the id the dispatch and the callbacks will carry.
     */
    @Transactional
    @Modifying
    @Query(value = "update job_queue set prepared_at = :at, dispatch_payload = :payload, prepare_lease_until = null, "
        + "correlation_id = COALESCE(correlation_id, :correlationId) "
        + "where job_queue_id = :id and UPPER(job_status) = 'QUEUE' and job_send = false", nativeQuery = true)
    int markPrepared(@Param("id") Long jobQueueId, @Param("payload") String payload, @Param("at") Timestamp at,
        @Param("correlationId") String correlationId);

    /** Core's own dials (V88, MIG-136); null when the setting is missing. */
    @Query(value = "select setting_value from orchestration_setting where setting_key = ?1", nativeQuery = true)
    String findOrchestrationSetting(String settingKey);

    // MIG-347: the job's own lane (0): a backfill's runs, in lanes of their own, do not make the job busy. V220: nor do a
    // workflow's runs (one per request, beside the job's own).
    @Query(value = "select count(*) from job_queue where job_id = ?1 and run_lane = 0 and UPPER(job_status) in ('QUEUE', 'START', 'RUNNING') "
        + "and workflow_instance_id is null", nativeQuery = true)
    int getCountForInQueueJobByJobId(Long jobId);

    /** V220: a workflow request's run of the job still in flight, if any -- what a request asking twice is answered with. */
    @Query(value = "select job_queue_id from job_queue where job_id = ?1 and workflow_instance_id = ?2 and tenant_id = ?3 "
        + "and UPPER(job_status) in ('QUEUE', 'START', 'RUNNING') limit 1", nativeQuery = true)
    Long findInFlightWorkflowRun(Long jobId, Long workflowInstanceId, Long tenantId);

    @Query(value = "select count(*) from job_queue where job_id = ?1", nativeQuery = true)
    int getCountForJobByJobId(Long jobId);

    /**
     * Runs that say they are still going long after anything real would have finished.
     *
     * A worker that completes its work and then cannot report back -- a restart, a dropped
     * connection -- leaves its row in Start for ever. That is not just a wrong row: the
     * dispatcher counts anything in Queue, Start or Running when deciding whether a job is
     * already busy, so one stranded run stops that job ever being scheduled again and it
     * accumulates "already in queue" skips instead.
     *
     * <b>QUEUE is included, and it is measured from date_created rather than start_time.</b>
     * This covered START and RUNNING only, which left the one strand nothing could ever clear: a
     * run whose task has no source task type was never dispatched at all, so it has no start_time
     * and sat in Queue for ever -- counted by the dispatcher as "already busy" and invisible to
     * the sweep that exists to clear exactly that. The dispatcher's own guard against that state
     * now closes the run when it happens, but only for new ones; this is what reaches the rows
     * already stranded, and any other way a row can be enqueued and never picked up.
     *
     * COALESCE rather than a second query: a Queue row has no start_time by definition, and a
     * START row that somehow has none would otherwise be excluded by the null test the way the
     * Queue rows were.
     */
    @Query(value = "select job_queue.* from job_queue "
        + "where UPPER(job_status) in ('QUEUE', 'START', 'RUNNING') "
        + "and COALESCE(start_time, date_created) is not null "
        + "and COALESCE(start_time, date_created) < ?1 "
        // MIG-346: a run waiting at an Approval step is not silent -- its request has its own expiry (the approval sweep) --
        // and one decided within the sweep's six hours (ProducerBulkEngine.STALLED_AFTER_MINUTES) has only just started its
        // remaining steps. decided_at is an instant, so it is compared with now(), not with the wall-clock cutoff.
        + "and not exists (select 1 from run_approval a where a.job_queue_id = job_queue.job_queue_id "
        + "and (a.status = 'PENDING' or a.decided_at >= now() - interval '360 minutes')) "
        + "order by job_queue_id asc", nativeQuery = true)
    List<JobQueue> findStalledRuns(Timestamp startedBefore);

    /**
     * ZT-P16: runs the step engine held whose hold lapsed more than a minute ago -- the Core that ran them is gone (a restart,
     * a deploy): in flight, sent, and prepare_lease_until (the engine's hold once a run is sent, renewed every 30 s) in the past.
     * A run waiting for an approval has no hold. The database's clock on both sides, as the engine writes it.
     */
    @Query(value = "select job_queue.* from job_queue where UPPER(job_status) in ('QUEUE', 'START', 'RUNNING') and job_send = true "
        + "and prepare_lease_until is not null and prepare_lease_until < now() - interval '60 seconds' "
        + "order by job_queue_id asc", nativeQuery = true)
    List<JobQueue> findRunsWithLapsedEngineLease();

    /** ZT-P16: a closed run's steps that were still running or waiting, closed with it. */
    @Transactional
    @Modifying
    @Query(value = "update step_execution set status = 'Interrupt', status_message = ?2, date_updated = now() "
        + "where job_queue_id = ?1 and status in ('Queue', 'Start', 'Running')", nativeQuery = true)
    int interruptOpenSteps(Long jobQueueId, String message);

    /**
     * Notes on a run still in flight that its own worker's report was refused for an expired token
     * (MIG-63). Guarded on the in-flight statuses so a late report on a finished run marks nothing.
     */
    @Transactional
    @Modifying
    @Query(value = "update job_queue set refused_callback_at = ?2, refused_callback_status = ?3 "
        + "where job_queue_id = ?1 and UPPER(job_status) in ('QUEUE', 'START', 'RUNNING')", nativeQuery = true)
    int noteRefusedCallback(Long jobQueueId, Timestamp refusedAt, String reportedStatus);

    /** Runs still in flight whose worker is known to be unable to report: the stall sweep closes them now. */
    @Query(value = "select job_queue.* from job_queue "
        + "where UPPER(job_status) in ('QUEUE', 'START', 'RUNNING') "
        + "and refused_callback_at is not null "
        + "order by job_queue_id asc", nativeQuery = true)
    List<JobQueue> findRunsWithRefusedCallbacks();

    List<JobQueue> findAllByJobId(Long jobId);

    /**
     * A job's runs, newest first, one window at a time (scale review P0 #1). findAllByJobId above hands
     * over the job's whole history -- 525,000 rows a year for a job that runs every minute -- and its
     * callers sorted it in Java to keep a handful. This is the window in SQL, on (job_id, job_queue_id)
     * (V200), so a read costs the rows it returns. beforeId is the keyset: Long.MAX_VALUE for the newest
     * window, the oldest id already shown for the next one.
     */
    @Query(value = "select job_queue.* from job_queue where job_id = :jobId and job_queue_id < :beforeId "
        + "order by job_queue_id desc limit :limit", nativeQuery = true)
    List<JobQueue> findRecentByJobId(@Param("jobId") Long jobId, @Param("beforeId") long beforeId, @Param("limit") int limit);

    /** The newest run of a job after a given one, or null: what a rerun queued (RunReviewService), without the history. V220: never a
     * workflow's run of the job, which runs beside it. */
    @Query(value = "select max(job_queue_id) from job_queue where job_id = :jobId and job_queue_id > :afterId and workflow_instance_id is null",
        nativeQuery = true)
    Long findNewestRunIdAfter(@Param("jobId") Long jobId, @Param("afterId") long afterId);

    /** A job's runs counted by status in the database, for the assistant's totals (rows: status, count). */
    @Query(value = "select UPPER(job_status), count(*) from job_queue where job_id = ?1 group by UPPER(job_status)", nativeQuery = true)
    List<Object[]> countByStatusForJob(Long jobId);

    /**
     * Which of these jobs have ever run (scale review P1 #20). The list only needs presence, so it is an
     * exists() per job rather than a count of every run, and the ids travel as ONE array parameter: an IN
     * list binds a parameter per id and stops at 32,767. Pass the ids through {@link #idArray}.
     */
    @Query(value = "select j.id from unnest(cast(:jobIds as bigint[])) as j(id) "
        + "where exists (select 1 from job_queue q where q.job_id = j.id)", nativeQuery = true)
    List<Number> findJobIdsWithRuns(@Param("jobIds") String jobIds);

    /**
     * Billing's names for runs it has costs for (UX review 2026-10-12, BL1): each of these runs that belongs to the
     * workspace, with its schedule (source_job) and pipeline (source_task) and when it started -- else when it was
     * skipped, else when it was queued. {job_queue_id, job_id, job_name, task_detail_id, task_name, started_at,
     * job_status}. The ids travel as ONE array parameter ({@link #idArray}); the workspace is both the filter and, under
     * row-level security, the only one the session sees.
     */
    @Query(value = "select q.job_queue_id, q.job_id, j.job_name, j.task_detail_id, t.task_name, "
        + "coalesce(q.start_time, q.skip_time, q.date_created) as started_at, q.job_status "
        + "from unnest(cast(:ids as bigint[])) as r(id) join job_queue q on q.job_queue_id = r.id "
        + "join source_job j on j.job_id = q.job_id left join source_task t on t.task_detail_id = j.task_detail_id "
        + "where q.tenant_id = :tenantId order by q.job_queue_id", nativeQuery = true)
    List<Object[]> findRunNames(@Param("tenantId") long tenantId, @Param("ids") String ids);

    /** Ids as a Postgres array literal, '{1,2,3}', for a cast(:ids as bigint[]) parameter. */
    static String idArray(Collection<Long> ids) {
        return ids.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    @Transactional
    @Modifying
    @Query(value = "update job_queue set status = ?2 where job_id = ?1", nativeQuery = true)
    int updateStatusByJobId(Long jobId, String statusName);


    /**
     * The most recent runs of the jobs assigned to one person.
     *
     * Scoped by assigned_user_id, which is the caller's own id, and by the caller's workspace: a user
     * belongs to one workspace, but nothing in the schema ties a job's assignee to the job's workspace,
     * so the id alone would also reach another workspace's job that names them (MIG-166). allTenants
     * is a platform admin's grant.
     *
     * Ordered by start_time with the nulls last: a queued run that has not begun has no start
     * time, and it belongs at the bottom rather than sorted as though it were the oldest thing
     * there. job_queue_id breaks ties, since two runs of the same job can share a second.
     */
    @Query(value = "select q.job_queue_id, q.job_id, j.job_name, q.job_status, q.start_time, "
        + "q.end_time, q.job_status_message "
        + "from job_queue q join source_job j on j.job_id = q.job_id "
        // A deleted job's runs are not the person's activity any more: the job tiles beside this
        // list already leave them out, and so does the report. Without the clause a deleted job
        // kept appearing here, with a link to a job that no longer opens.
        + "where j.assigned_user_id = :appUserId and j.job_status <> 'Delete' "
        + "and (:allTenants = true or j.tenant_id = :tenantId) "
        + "order by q.start_time desc nulls last, q.job_queue_id desc limit :limit", nativeQuery = true)
    List<Object[]> findRecentRunsForAssignee(@Param("appUserId") Long appUserId, @Param("allTenants") boolean allTenants,
        @Param("tenantId") long tenantId, @Param("limit") int limit);

    /** How many of that person's runs started inside the window, and how many of those failed. */
    // Aliased for the same reason as countAssignedTo: two unaliased count(*) columns both come
    // back named "count", which Hibernate's auto-discovery rejects with
    // NonUniqueDiscoveredSqlAliasException. This is the second half of the /profile 500.
    @Query(value = "select count(*) as total_count, "
        + "count(*) filter (where UPPER(q.job_status) = 'FAILED') as failed_count "
        + "from job_queue q join source_job j on j.job_id = q.job_id "
        + "where j.assigned_user_id = :appUserId and j.job_status <> 'Delete' and q.start_time >= :since "
        + "and (:allTenants = true or j.tenant_id = :tenantId)", nativeQuery = true)
    List<Object[]> countRecentRunsForAssignee(@Param("appUserId") Long appUserId, @Param("allTenants") boolean allTenants,
        @Param("tenantId") long tenantId, @Param("since") Timestamp since);

}
