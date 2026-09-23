package process.media.converter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.media.converter.DocumentConverterTask;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface DocumentConverterTaskRepository extends JpaRepository<DocumentConverterTask, Long> {

    List<DocumentConverterTask> findByStatusNotOrderByDocumentConverterTaskIdDesc(Status status);

}
