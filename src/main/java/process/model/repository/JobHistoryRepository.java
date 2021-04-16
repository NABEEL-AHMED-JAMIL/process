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

    @Query(value = "select job_history.* from job_history where job_status = 'Queue' limit ?1", nativeQuery = true)
    public List<JobHistory> findAllJobForTodayWithLimit(Long limit);

}

