package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.PdfHighlighterField;
import java.util.List;

@Repository
public interface PdfHighlighterFieldRepository extends JpaRepository<PdfHighlighterField, Long> {

    public List<PdfHighlighterField> findByPdfHighlighterTaskIdOrderByDisplayOrderAsc(Long pdfHighlighterTaskId);

    @Transactional
    @Modifying
    public void deleteByPdfHighlighterTaskId(Long pdfHighlighterTaskId);

}
