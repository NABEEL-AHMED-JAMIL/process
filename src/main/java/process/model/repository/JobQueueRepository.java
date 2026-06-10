package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.JobQueue;
import process.model.enums.Status;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface JobQueueRepository extends CrudRepository<JobQueue, Long> {

    /**
     * Note :- Method use to get the JobQueue by limit if present in db
     * @param limit
     * @return List<JobQueue>
     * */
    @Query(value = "select job_queue.* from job_queue where UPPER(job_status) = 'QUEUE' and job_send = false limit ?1 ", nativeQuery = true)
    public List<JobQueue> findAllJobForTodayWithLimit(Long limit);

    /**
     * Note :- Method use to get the source job count from the job queue base on 'Queue|Running'
     * @param jobId
     * @return int
     * */
    @Query(value = "select count(*) from job_queue where job_id = ?1 and UPPER(job_status) in ('QUEUE', 'START', 'RUNNING')", nativeQuery = true)
    public int getCountForInQueueJobByJobId(Long jobId);

    /**
     * Note :- Method use to get the source job count from the job queue
     * @param jobId
     * @return int
     * */
    @Query(value = "select count(*) from job_queue where job_id = ?1", nativeQuery = true)
    public int getCountForJobByJobId(Long jobId);

    /**
     * Find all job_queue rows for a given job id
     * @param jobId
     * @return List<JobQueue>
     */
    public java.util.List<JobQueue> findAllByJobId(Long jobId);

    /**
     * Bulk update status for job_queue rows by job id
     * @param jobId
     * @param status
     * @return number of rows updated
     */
    @Transactional
    @Modifying
    @Query(value = "update job_queue set status = ?2 where job_id = ?1", nativeQuery = true)
    int updateStatusByJobId(Long jobId, Status status);


}