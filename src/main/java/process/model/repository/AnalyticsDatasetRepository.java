package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.AnalyticsDataset;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface AnalyticsDatasetRepository extends JpaRepository<AnalyticsDataset, Long> {

    // Unscoped by itself: the caller has to have enabled the tenant filter first, exactly as the
    // other tenant-scoped listings do. Spelled out because the method name says nothing about a
    // tenant, and a reader should not have to infer that from silence.
    List<AnalyticsDataset> findAllByOrderByAnalyticsDatasetIdDesc();

    List<AnalyticsDataset> findByTenantIdOrderByAnalyticsDatasetIdDesc(Long tenantId);

}
