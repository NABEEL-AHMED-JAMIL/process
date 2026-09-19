package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.pojo.Invoice;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByNumber(String number);
    List<Invoice> findByTenantIdOrderByPeriodStartDescInvoiceIdDesc(Long tenantId);
    List<Invoice> findAllByOrderByPeriodStartDescInvoiceIdDesc();
    Optional<Invoice> findFirstByTenantIdAndPeriodStartAndKindAndStatus(Long tenantId, LocalDate periodStart, String kind, String status);
    List<Invoice> findByStatusIn(List<String> statuses);
    List<Invoice> findByPeriodStartBetweenOrderByTenantIdAscPeriodStartAsc(LocalDate from, LocalDate to);
    @Query("select count(i) from Invoice i where i.number like concat(:prefix, '%')")
    long countByNumberPrefix(@Param("prefix") String prefix);
}
