package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.Scheduler;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SchedulerRepository extends CrudRepository<Scheduler, Long> {

    /**
     * Note :- this method use to fetch the only this scheduler which are active and match with current
     * time slot we give the current time from the system time zone bz on db timezone diff
     * @param todayDate
     * @return List<Scheduler>
     * */
    @Query(value = "select scheduler.* from scheduler\n" +
        "inner join source_job on scheduler.job_id=source_job.job_id\n" +
        "where ((?1 BETWEEN start_date AND end_date) OR (start_date <= ?1 AND end_date is null))\n" +
        "and source_job.job_status = 'Active'", nativeQuery = true)
    public List<Scheduler> findAllSchedulerForToday(LocalDate todayDate);

    /**
     * Note :- this method use to fetch the only this scheduler which are active and match with current
     * time slot we give the current time from the system time zone bz on db timezone diff
     * @param lastSchedulerRun
     * @param currentSchedulerTime
     * @return List<Scheduler>
     * */
    @Query(value = "select scheduler.* from scheduler\n" +
        "inner join source_job on scheduler.job_id=source_job.job_id\n" +
        "where scheduler.recurrence_time between ?1 and ?2\n" +
        "and source_job.job_status = 'Active'", nativeQuery = true)
    public List<Scheduler> findAllSchedulerForToday(LocalDateTime lastSchedulerRun, LocalDateTime currentSchedulerTime);

    public Optional<Scheduler> findSchedulerByJobId(Long jobId);

}