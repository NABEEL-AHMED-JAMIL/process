package process.model.repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.JobQueue;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface JobQueueRepository extends CrudRepository<JobQueue, Long> {

    /**
     * Method use to get the JobQueue by limit if present in db
     * @param limit
     * @return List<JobQueue>
     * */
    @Query(value = "select job_queue.* from job_queue where job_status = 'Queue' limit ?1", nativeQuery = true)
    public List<JobQueue> findAllJobForTodayWithLimit(Long limit);

    /**
     * Method use to get the source job count from the job queue base on 'Queue|Running'
     * @param jobId
     * @return int
     * */
    @Query(value = "select count(*) from job_queue where job_id = ?1 and job_status in ('Queue', 'Running')", nativeQuery = true)
    public int getCountForInQueueJobByJobId(Long jobId);

}