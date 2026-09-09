package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.AnalyticsDashboard;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface AnalyticsDashboardRepository extends JpaRepository<AnalyticsDashboard, Long> {

    // Unscoped by itself: the caller has to have enabled the tenant filter first, exactly as the
    // other tenant-scoped listings do. Spelled out because the method name says nothing about a
    // tenant, and a reader should not have to infer that from silence.
    List<AnalyticsDashboard> findAllByOrderByAnalyticsDashboardIdDesc();

    /**
     * The same row findById would return, fetched as a query so the tenant filter applies.
     *
     * A Hibernate @Filter is applied to queries and NOT to a load by primary key. The service
     * re-checks ownership on top of this anyway, because neither layer is trusted to be the only
     * one.
     */
    Optional<AnalyticsDashboard> findByAnalyticsDashboardId(Long analyticsDashboardId);

}
