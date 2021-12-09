package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.pojo.JobHistory;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface JobHistoryRepository extends JpaRepository<JobHistory, Long> {

    /**
     * Method use to get the JobHistory by limit if present in db
     * @param limit
     * @return List<JobHistory>
     * */
    @Query(value = "select job_history.* from job_history where job_status = 'Queue' limit ?1", nativeQuery = true)
    public List<JobHistory> findAllJobForTodayWithLimit(Long limit);

    /**
     * Method use to get the JobHistory by jobId if present in db
     * @param jobId
     * @return List<JobHistory>
     * */
    @Query(value = "select count(*) from job_history where job_id = ?1 and job_status in('Queue','Running');", nativeQuery = true)
    public List<JobHistory> findJobHistoryCount(Long jobId);

}