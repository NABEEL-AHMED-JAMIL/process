package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.Scheduler;
import java.time.LocalDate;
import java.util.List;

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
    @Query(value = "select scheduler.* from scheduler join source_job on scheduler.job_id=source_job.job_id\n" +
        "where ((?1 BETWEEN start_date AND end_date) OR (start_date <= ?1 AND end_date is null))\n" +
        "and source_job.job_status = 'Active' and (source_job.job_running_status is null or source_job.job_running_status = 'InFlight')", nativeQuery = true)
    public List<Scheduler> findAllSchedulerForToday(LocalDate todayDate);

}