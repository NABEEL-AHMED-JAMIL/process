package process.model.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.pojo.AnalyticsQueryRun;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface AnalyticsQueryRunRepository extends JpaRepository<AnalyticsQueryRun, Long> {

    // Both take a Pageable and neither has an unbounded variant, on purpose: this table is never
    // pruned (see V32__analytics_query.sql for why), so an unbounded read of it would grow
    // without limit even though the table is meant to.
    //
    // Ordered by date rather than by id so the read matches idx_analytics_query_run_tenant_date,
    // with the id as the tie-break because two runs can share a millisecond. Unscoped by
    // themselves: the caller enables the tenant filter first.
    List<AnalyticsQueryRun> findAllByOrderByDateCreatedDescAnalyticsQueryRunIdDesc(Pageable pageable);

    List<AnalyticsQueryRun> findByAnalyticsQueryIdOrderByDateCreatedDescAnalyticsQueryRunIdDesc(
        Long analyticsQueryId, Pageable pageable);

}
