package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.pojo.Scheduler;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface SchedulerRepository extends CrudRepository<Scheduler, Long> {

    /**
     * Claims the next slot the enqueuer should queue, for the caller's transaction (MIG-152).
     *
     * One row, the oldest due first, locked FOR UPDATE SKIP LOCKED: any number of replicas run this
     * loop at once with no coordinator, each taking a slot no other holds, and the claim, the run it
     * enqueues and the advance of next_run_at commit or roll back together -- a replica that dies part-way
     * leaves the slot exactly as it found it. It used to be every due scheduler in one unordered,
     * unlimited list under an exclusive ShedLock.
     *
     * dispatch_eligible carries the rest of what used to be a join to source_job -- Active, and
     * execution = 'Auto'. That clause is load-bearing: switching a job from Auto to Manual leaves its
     * scheduler row unexpired with next_run_at intact, and without it the cron kept queueing a job the
     * operator had just told it to stop running on its own (V84 keeps the flag by trigger).
     *
     * {@code passed} is the slots this pass has already given up on, so one that fails every time does not
     * hold the loop; it always holds at least one id, because NOT IN () is not SQL.
     */
    @Query(value = "select scheduler.* from scheduler "
        + "where scheduler.dispatch_eligible and scheduler.expired = false and scheduler.next_run_at <= :now "
        + "and scheduler.scheduler_id not in (:passed) "
        + "order by scheduler.next_run_at asc, scheduler.scheduler_id asc "
        + "limit 1 for update skip locked", nativeQuery = true)
    public Optional<Scheduler> claimNextDueScheduler(@Param("now") Timestamp now, @Param("passed") List<Long> passed);

    public Optional<Scheduler> findSchedulerByJobId(Long jobId);

    public List<Scheduler> findByJobIdIn(List<Long> jobIds);

}
