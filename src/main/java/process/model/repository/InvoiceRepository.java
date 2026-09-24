package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.Invoice;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByNumber(String number);
    List<Invoice> findByReferencesInvoiceId(Long invoiceId);
    List<Invoice> findByTenantIdOrderByPeriodStartDescInvoiceIdDesc(Long tenantId);
    List<Invoice> findAllByOrderByPeriodStartDescInvoiceIdDesc();
    Optional<Invoice> findFirstByTenantIdAndPeriodStartAndKindAndStatus(Long tenantId, LocalDate periodStart, String kind, String status);
    /** The month's invoice once it has left draft -- issued, paid, overdue -- ignoring any that were voided. */
    Optional<Invoice> findFirstByTenantIdAndPeriodStartAndKindAndStatusNotIn(Long tenantId, LocalDate periodStart, String kind, List<String> statuses);
    List<Invoice> findByStatusIn(List<String> statuses);
    List<Invoice> findByPeriodStartBetweenOrderByTenantIdAscPeriodStartAsc(LocalDate from, LocalDate to);
}
