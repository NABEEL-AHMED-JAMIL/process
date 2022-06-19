package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
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

}