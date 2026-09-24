package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.pojo.InvoiceLine;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
/**
 * Lines by invoice id AND tenant, never by invoice id alone (MIG-47): the tenant filter only runs where a
 * session enabled it, and the invoice id is whatever the caller sent. InvoiceLineRepositoryGuardTest holds it.
 */
public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, Long> {
    List<InvoiceLine> findByInvoiceIdAndTenantIdOrderBySortAsc(Long invoiceId, Long tenantId);
    @Modifying
    @Query("delete from InvoiceLine l where l.invoiceId = :invoiceId and l.tenantId = :tenantId")
    void deleteByInvoiceIdAndTenantId(@Param("invoiceId") Long invoiceId, @Param("tenantId") Long tenantId);
}
