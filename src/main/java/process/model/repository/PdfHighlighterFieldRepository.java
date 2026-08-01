package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.PdfHighlighterField;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface PdfHighlighterFieldRepository extends JpaRepository<PdfHighlighterField, Long> {

    public List<PdfHighlighterField> findByPdfHighlighterTaskIdOrderByDisplayOrderAsc(Long pdfHighlighterTaskId);

    /**
     * Note :- derived delete queries need their own transaction (unlike deleteById, this
     * isn't wrapped by SimpleJpaRepository) -- without @Transactional here this throws
     * TransactionRequiredException when called from a non-@Transactional service method.
     * */
    @Transactional
    @Modifying
    public void deleteByPdfHighlighterTaskId(Long pdfHighlighterTaskId);

}
