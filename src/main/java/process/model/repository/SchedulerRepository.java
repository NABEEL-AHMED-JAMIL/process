package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.Scheduler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SchedulerRepository extends CrudRepository<Scheduler, Long> {

    @Query(value = "select scheduler.* from scheduler\n" +
        "inner join source_job on scheduler.job_id=source_job.job_id\n" +
        "where scheduler.recurrence_time between ?1 and ?2\n" +
        "and source_job.job_status = 'Active'", nativeQuery = true)
    public List<Scheduler> findAllSchedulerForToday(LocalDateTime lastSchedulerRun, LocalDateTime currentSchedulerTime);

    public Optional<Scheduler> findSchedulerByJobId(Long jobId);

}