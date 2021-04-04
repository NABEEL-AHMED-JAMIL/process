package process.model.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.Job;
import process.model.repository.projection.JobViewProjection;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    /**
     * Method use to get the job by status if present in db
     * @param jobName
     * @param status
     * @return Optional<Job>
     * */
    Optional<Job> findByJobNameAndJobStatus(String jobName, Status status);

    Optional<Job> findByJobIdAndJobStatus(Long jobId, Status status);

    /**
     * This method use to fetch the data for list
     * @param pageRequest
     * @return Page<JobViewProjection>
     * */
    @Query(value = "select job.job_id as jobId, job.job_Name as jobName, job.trigger_detail as triggerDetail, job.job_status as jobStatus,\n" +
        "job.job_running_status as jobRunningStatus, job.last_job_run as lastJobRun, job.next_job_run as nextJobRun, job.date_created as dateCreated,\n" +
        "scheduler.frequency as frequency, count(job_history.job_id) as runningTasks from job as job\n" +
        "inner join scheduler as scheduler on scheduler.job_id = job.job_id\n" +
        "left join job_history as job_history on job_history.job_id = job.job_id\n" +
        "where job.job_status = 'Active' group by job.job_id, scheduler.frequency", nativeQuery = true)
    Page<JobViewProjection> findByJobStatus(PageRequest pageRequest);

}
