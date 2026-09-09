package process.model.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.sql.Timestamp;
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

    /**
     * Deletes history older than a cut-off, and answers with how many rows went.
     *
     * A bulk @Modifying delete rather than findAll-then-deleteAll: the retention window is the
     * whole point, and loading a year of audit rows into the heap to delete them would make the
     * cleanup the most expensive thing this table ever does to the application.
     *
     * NOT tenant-scoped, and it must not be. Retention is an operator policy over the table, not
     * a tenant's view of it, and Hibernate's tenant filter is off on this path deliberately -- a
     * filtered delete would silently keep every row belonging to tenants nobody was signed in as.
     *
     * Rides idx_analytics_query_run_tenant_date, whose second column is date_created; the
     * changeset that created it says so, and that index is the reason this is affordable.
     */
    @Modifying
    @Query("DELETE FROM AnalyticsQueryRun r WHERE r.dateCreated < :before")
    int deleteByDateCreatedBefore(@Param("before") Timestamp before);

}
