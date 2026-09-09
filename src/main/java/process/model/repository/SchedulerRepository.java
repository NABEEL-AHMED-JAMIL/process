package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.Scheduler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface SchedulerRepository extends CrudRepository<Scheduler, Long> {

    /**
     * The schedulers the dispatch cron should queue right now.
     *
     * The execution filter is load-bearing. Switching a job from Auto to Manual in the console
     * sends no `schedulers` block, and updateSourceJob only touches the Scheduler row when one
     * is present -- so the row survives the switch, unexpired and with its next_run_at intact.
     * Without this clause the cron kept finding it and queueing a job the operator had just told
     * it to stop running on its own. Filtering here rather than expiring the row on switch also
     * fixes every job already left in that state, and keeps the schedule intact so switching
     * back to Auto resumes it rather than losing it.
     */
    @Query(value = "select scheduler.* from scheduler\n" +
        "inner join source_job on scheduler.job_id=source_job.job_id\n" +
        "where scheduler.next_run_at <= ?1\n" +
        "and scheduler.expired = false\n" +
        "and source_job.job_status = 'Active'\n" +
        "and source_job.execution = 'Auto'", nativeQuery = true)
    public List<Scheduler> findDueSchedulers(LocalDateTime now);

    public Optional<Scheduler> findSchedulerByJobId(Long jobId);

    public List<Scheduler> findByJobIdIn(List<Long> jobIds);

}
