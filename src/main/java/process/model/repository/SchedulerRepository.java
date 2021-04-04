package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;
import process.model.pojo.Scheduler;

import javax.persistence.QueryHint;
import java.time.LocalDate;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SchedulerRepository extends JpaRepository<Scheduler, Long> {

    /**
     * Note :- this method use to fetch the only those scheduler which are active and match with current
     * time slot we give the current time from the system time zone bz on db timezone diff
     * @param todayDate
     * @return List<Scheduler>
     * */
    @QueryHints({@QueryHint(name = "javax.persistence.lock.timeout", value = "-2")})
    @Query(value = "select scheduler.* from scheduler\n" +
        "join job on scheduler.job_id=job.job_id\n" +
        "where ((?1 BETWEEN start_date AND end_date) OR (start_date <= ?1 AND end_date is null))\n" +
        "and job.job_status = 'Active' and (job.job_running_status is null or job.job_running_status = 'InFlight')\n" +
        "for update skip locked",nativeQuery = true)
    public List<Scheduler> findAllSchedulerForToday(LocalDate todayDate);

}
