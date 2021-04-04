package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;
import process.model.pojo.JobHistory;

import javax.persistence.QueryHint;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface JobHistoryRepository extends JpaRepository<JobHistory, Long> {

    @QueryHints({@QueryHint(name = "javax.persistence.lock.timeout", value = "-2")})
    @Query(value = "select job_history.* from job_history where job_status = 'Queue' limit ?1\n"+
            "for update skip locked", nativeQuery = true)
    public List<JobHistory> findAllJobForTodayWithLimit(Long limit);
}

