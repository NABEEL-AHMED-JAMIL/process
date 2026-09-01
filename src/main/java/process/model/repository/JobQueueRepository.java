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
    @Query(value = "select job_queue.* from job_queue where UPPER(job_status) = 'QUEUE' and job_send = false "
        + "order by job_queue_id asc limit ?1 ", nativeQuery = true)
    public List<JobQueue> findAllJobForTodayWithLimit(Long limit);

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
     */
    @Query(value = "select job_queue.* from job_queue "
        + "where UPPER(job_status) in ('START', 'RUNNING') "
        + "and start_time is not null and start_time < ?1 "
        + "order by job_queue_id asc", nativeQuery = true)
    public List<JobQueue> findStalledRuns(LocalDateTime startedBefore);

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
        + "where j.assigned_user_id = ?1 "
        + "order by q.start_time desc nulls last, q.job_queue_id desc limit ?2", nativeQuery = true)
    List<Object[]> findRecentRunsForAssignee(Long appUserId, int limit);

    /** How many of that person's runs started inside the window, and how many of those failed. */
    @Query(value = "select count(*), "
        + "count(*) filter (where UPPER(q.job_status) = 'FAILED') "
        + "from job_queue q join source_job j on j.job_id = q.job_id "
        + "where j.assigned_user_id = ?1 and q.start_time >= ?2", nativeQuery = true)
    List<Object[]> countRecentRunsForAssignee(Long appUserId, LocalDateTime since);

}