package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.Status;
import process.model.pojo.PdfHighlighterTask;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface PdfHighlighterTaskRepository extends JpaRepository<PdfHighlighterTask, Long> {

    /** Phase 0 migration backfill -- see TenantSeedService. */
    @Transactional
    @Modifying
    @Query("update PdfHighlighterTask p set p.tenantId = ?1 where p.tenantId is null")
    int backfillTenantId(Long tenantId);

    /**
     * Note :- Method use to fetch all pdf highlighter task ordered by newest first,
     * excluding deleted ones.
     * @param status
     * @return List<PdfHighlighterTask>
     * */
    public List<PdfHighlighterTask> findByStatusNotOrderByPdfHighlighterTaskIdDesc(Status status);

}
