package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.projection.SourceJobProjection;
import javax.transaction.Transactional;
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
    public Optional<SourceJob> findByJobIdAndJobStatus(Long jobId, Status status);

    /**
     * Method use to get the job by status if present in db
     * @param jobName
     * @param status
     * @return Optional<Job>
     * */
    public Optional<SourceJob> findByJobNameAndJobStatus(String jobName, Status status);

    /**
     * Method use to get the job by status if present in db
     * @param jobRunningStatus
     * @return List<Long>
     * */
    public List<SourceJob> findJobByJobRunningStatus(JobStatus jobRunningStatus);

    /**
     * Method use to get the job detail which use to update the view table
     * @param jobIds
     * @return List<SourceJobProjection>
     * */
    @Query(value = "select sj.job_id as jobId, sj.job_status as jobStatus, sj.job_running_status as jobRunningStatus," +
        "sj.last_job_run as lastJobRun, sc.recurrence_time as recurrenceTime, sj.execution as execution\n" +
        "from source_job sj left join scheduler sc on sc.job_id = sj.job_id\n" +
        "where sj.job_id in (?1) and sj.job_status = 'Active'", nativeQuery = true)
    public List<SourceJobProjection> fetchRunningJobEvent(List<Integer> jobIds);

    @Modifying
    @Transactional
    @Query(value = "update source_job set job_status = ?2\n" +
        "where task_detail_id in (select task_detail_id from source_task where source_task_type_id = ?1)\n",
        nativeQuery = true)
    public int statusChangeSourceJobLinkWithSourceTaskTypeId(Long sourceTaskTypeId, String status);

    @Modifying
    @Transactional
    @Query(value = "update source_job set job_status = ?2 where task_detail_id = ?1", nativeQuery = true)
    public int statusChangeSourceJobWithSourceTaskId(Long sourceTaskId, String status);

}