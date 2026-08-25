package process.model.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.JobQueue;
import java.util.List;

@Repository
public interface JobQueueRepository extends CrudRepository<JobQueue, Long> {

    // Ordered by id: the fetch is capped, so without this which rows a busy queue hands over is
    // whatever the planner returns, and a job can sit behind newer ones indefinitely.
    @Query(value = "select job_queue.* from job_queue where UPPER(job_status) = 'QUEUE' and job_send = false "
        + "order by job_queue_id asc limit ?1 ", nativeQuery = true)
    public List<JobQueue> findAllJobForTodayWithLimit(Long limit);

    @Query(value = "select count(*) from job_queue where job_id = ?1 and UPPER(job_status) in ('QUEUE', 'START', 'RUNNING')", nativeQuery = true)
    public int getCountForInQueueJobByJobId(Long jobId);

    @Query(value = "select count(*) from job_queue where job_id = ?1", nativeQuery = true)
    public int getCountForJobByJobId(Long jobId);

    @Query(value = "select job_id, count(*) from job_queue where job_id in :jobIds group by job_id", nativeQuery = true)
    public List<Object[]> countGroupByJobIds(@Param("jobIds") List<Long> jobIds);

    public List<JobQueue> findAllByJobId(Long jobId);

    @Transactional
    @Modifying
    @Query(value = "update job_queue set status = ?2 where job_id = ?1", nativeQuery = true)
    int updateStatusByJobId(Long jobId, String statusName);

}