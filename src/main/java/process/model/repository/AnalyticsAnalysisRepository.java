package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.AnalyticsAnalysis;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface AnalyticsAnalysisRepository extends JpaRepository<AnalyticsAnalysis, Long> {

    // Unscoped by itself: the caller has to have enabled the tenant filter first, exactly as the
    // other tenant-scoped listings do. Spelled out because the method name says nothing about a
    // tenant, and a reader should not have to infer that from silence.
    List<AnalyticsAnalysis> findAllByOrderByAnalyticsAnalysisIdDesc();

    /**
     * The same row findById would return, fetched as a query so the tenant filter applies.
     *
     * A Hibernate @Filter is applied to queries and NOT to a load by primary key, so findById
     * reaches straight past it into another workspace's row. That is why this method exists and
     * why the service checks ownership on top of it as well: neither layer is trusted to be the
     * only one.
     */
    Optional<AnalyticsAnalysis> findByAnalyticsAnalysisId(Long analyticsAnalysisId);

}
