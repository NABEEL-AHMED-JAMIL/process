package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.JobQueue;
import java.time.LocalDateTime;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface JobQueueRepository extends CrudRepository<JobQueue, Long> {

    // Ordered by id: the fetch is capped, so without this which rows a busy queue hands over is
    // whatever the planner returns, and a job can sit behind newer ones indefinitely.
    // next_attempt_at is how a backoff is enforced: a run awaiting retry sits in Queue like any
    // other, and is simply not eligible until the moment written on it. The cutoff arrives as a
    // parameter rather than being read here as now(), because the database's now() is UTC while
    // the application writes America/Chicago -- comparing the column against the database's clock
    // would make every backoff either instantly elapsed or five hours long, depending on sign.
    @Query(value = "select job_queue.* from job_queue where UPPER(job_status) = 'QUEUE' and job_send = false "
        + "and (next_attempt_at is null or next_attempt_at <= ?2) "
        + "order by job_queue_id asc limit ?1 ", nativeQuery = true)
    public List<JobQueue> findAllJobForTodayWithLimit(Long limit, LocalDateTime eligibleAt);

    @Query(value = "select count(*) from job_queue where job_id = ?1 and UPPER(job_status) in ('QUEUE', 'START', 'RUNNING')", nativeQuery = true)
    public int getCountForInQueueJobByJobId(Long jobId);

    @Query(value = "select count(*) from job_queue where job_id = ?1", nativeQuery = true)
    public int getCountForJobByJobId(Long jobId);

    @Query(value = "select job_id, count(*) from job_queue where job_id in :jobIds group by job_id", nativeQuery = true)
    public List<Object[]> countGroupByJobIds(@Param("jobIds") List<Long> jobIds);

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
        + "order by job_queue_id asc", nativeQuery = true)
    public List<JobQueue> findStalledRuns(LocalDateTime startedBefore);

    /**
     * Notes on a run still in flight that its own worker's report was refused for an expired token
     * (MIG-63). Guarded on the in-flight statuses so a late report on a finished run marks nothing.
     */
    @Transactional
    @Modifying
    @Query(value = "update job_queue set refused_callback_at = ?2, refused_callback_status = ?3 "
        + "where job_queue_id = ?1 and UPPER(job_status) in ('QUEUE', 'START', 'RUNNING')", nativeQuery = true)
    int noteRefusedCallback(Long jobQueueId, LocalDateTime refusedAt, String reportedStatus);

    /** Runs still in flight whose worker is known to be unable to report: the stall sweep closes them now. */
    @Query(value = "select job_queue.* from job_queue "
        + "where UPPER(job_status) in ('QUEUE', 'START', 'RUNNING') "
        + "and refused_callback_at is not null "
        + "order by job_queue_id asc", nativeQuery = true)
    public List<JobQueue> findRunsWithRefusedCallbacks();

    public List<JobQueue> findAllByJobId(Long jobId);

    @Transactional
    @Modifying
    @Query(value = "update job_queue set status = ?2 where job_id = ?1", nativeQuery = true)
    int updateStatusByJobId(Long jobId, String statusName);


    /**
     * The most recent runs of the jobs assigned to one person.
     *
     * Scoped by assigned_user_id, which is the caller's own id, so this cannot reach anybody
     * else's work whatever the tenant filter is doing -- a user belongs to one tenant and the
     * join only ever reaches jobs bearing their id.
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
        + "where j.assigned_user_id = ?1 and j.job_status <> 'Delete' "
        + "order by q.start_time desc nulls last, q.job_queue_id desc limit ?2", nativeQuery = true)
    List<Object[]> findRecentRunsForAssignee(Long appUserId, int limit);

    /** How many of that person's runs started inside the window, and how many of those failed. */
    // Aliased for the same reason as countAssignedTo: two unaliased count(*) columns both come
    // back named "count", which Hibernate's auto-discovery rejects with
    // NonUniqueDiscoveredSqlAliasException. This is the second half of the /profile 500.
    @Query(value = "select count(*) as total_count, "
        + "count(*) filter (where UPPER(q.job_status) = 'FAILED') as failed_count "
        + "from job_queue q join source_job j on j.job_id = q.job_id "
        + "where j.assigned_user_id = ?1 and j.job_status <> 'Delete' and q.start_time >= ?2", nativeQuery = true)
    List<Object[]> countRecentRunsForAssignee(Long appUserId, LocalDateTime since);

}