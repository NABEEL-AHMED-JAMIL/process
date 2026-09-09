package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.AnalyticsDashboardWidget;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface AnalyticsDashboardWidgetRepository extends JpaRepository<AnalyticsDashboardWidget, Long> {

    // Every read of this table starts from a dashboard -- there is no "all widgets" screen -- so
    // this is the only listing, and it is the shape idx_analytics_dashboard_widget_dashboard was
    // created for. The id breaks the tie because two widgets can share a display order.
    //
    // Unscoped by itself: the caller has to have enabled the tenant filter first.
    List<AnalyticsDashboardWidget> findByAnalyticsDashboardIdOrderByDisplayOrderAscAnalyticsDashboardWidgetIdAsc(
        Long analyticsDashboardId);

    /**
     * The same row findById would return, fetched as a query so the tenant filter applies.
     *
     * A Hibernate @Filter is applied to queries and NOT to a load by primary key. The service
     * re-checks ownership on top of this anyway, because neither layer is trusted to be the only
     * one.
     */
    Optional<AnalyticsDashboardWidget> findByAnalyticsDashboardWidgetId(Long analyticsDashboardWidgetId);

    // Both of these exist so a delete can say what it took with it, and so the tidying happens in
    // code rather than only in the database's cascade. The cascade covers a tenant's rows and NOT
    // a platform admin's -- Postgres treats a foreign key with a null column as satisfied, so it
    // never fires for a row whose tenant_id is null -- which would leave orphans behind exactly
    // for the caller who can see every dashboard.
    long countByAnalyticsAnalysisId(Long analyticsAnalysisId);

    List<AnalyticsDashboardWidget> findByAnalyticsAnalysisId(Long analyticsAnalysisId);

    List<AnalyticsDashboardWidget> findByAnalyticsQueryId(Long analyticsQueryId);

}
