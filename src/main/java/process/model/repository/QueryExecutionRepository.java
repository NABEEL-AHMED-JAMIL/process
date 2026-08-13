package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.QueryExecution;
import java.util.List;

@Repository
public interface QueryExecutionRepository extends JpaRepository<QueryExecution, Long> {

    List<QueryExecution> findByQueryIdOrderByExecutionIdDesc(Long queryId);

    List<QueryExecution> findTop50ByOrderByExecutionIdDesc();

}
