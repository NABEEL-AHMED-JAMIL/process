package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.PdfHighlighterTask;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface PdfHighlighterTaskRepository extends JpaRepository<PdfHighlighterTask, Long> {

    /**
     * Note :- Method use to fetch all pdf highlighter task ordered by newest first,
     * excluding deleted ones.
     * @param status
     * @return List<PdfHighlighterTask>
     * */
    public List<PdfHighlighterTask> findByStatusNotOrderByPdfHighlighterTaskIdDesc(Status status);

}
