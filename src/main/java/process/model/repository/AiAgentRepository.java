package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.AiAgent;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface AiAgentRepository extends JpaRepository<AiAgent, Long> {

    public List<AiAgent> findByStatusNotOrderByAiAgentIdDesc(Status status);

    public Optional<AiAgent> findByToolUuid(String toolUuid);

}
