package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.pojo.BillingDocument;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BillingDocumentRepository extends JpaRepository<BillingDocument, Long> {
    List<BillingDocument> findByTenantIdOrderByIssuedAtDesc(Long tenantId);
    List<BillingDocument> findByInvoiceIdOrderByIssuedAtAsc(Long invoiceId);
    List<BillingDocument> findAllByOrderByIssuedAtDesc();
}
