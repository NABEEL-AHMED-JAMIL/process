package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.projection.SourceJobProjection;
import java.util.Optional;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface SourceJobRepository extends JpaRepository<SourceJob, Long> {

    /**
     * Method use to get the job by status and the job id
     * @param jobId
     * @return status
     * @return Optional<Job>
     * */
    Optional<SourceJob> findByJobIdAndJobStatus(Long jobId, Status status);

    /**
     * Method use to get the job by status if present in db
     * @param jobName
     * @param status
     * @return Optional<Job>
     * */
    Optional<SourceJob> findByJobNameAndJobStatus(String jobName, Status status);

    /**
     * Method use to get the job by status if present in db
     * @param jobRunningStatus
     * @return List<Long>
     * */
    List<SourceJob> findJobByJobRunningStatus(JobStatus jobRunningStatus);

    /**
     * Method use to get the job detail which use to update the view table
     * @param jobIds
     * @return List<SourceJobProjection>
     * */
    @Query(value = "select sj.job_id as jobId, sj.job_status as jobStatus, sj.job_running_status as jobRunningStatus," +
        "sj.last_job_run as lastJobRun, sc.recurrence_time as recurrenceTime\n" +
        "from source_job sj left join scheduler sc on sc.job_id = sj.job_id\n" +
        "where sj.job_id in (?1) and sj.job_status = 'Active'", nativeQuery = true)
    List<SourceJobProjection> fetchRunningJobEvent(List<Integer> jobIds);

}