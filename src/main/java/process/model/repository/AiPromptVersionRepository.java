package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.AiPromptVersion;
import java.util.List;

@Repository
public interface AiPromptVersionRepository extends JpaRepository<AiPromptVersion, AiPromptVersion.Key> {
    List<AiPromptVersion> findAllByPromptIdOrderByVersionDesc(Long promptId);
}
