package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.pojo.Payment;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByInvoiceIdOrderByDateCreatedAsc(Long invoiceId);
    List<Payment> findByTenantIdOrderByDateCreatedDesc(Long tenantId);
    List<Payment> findByStatusOrderByDateCreatedAsc(String status);
}
